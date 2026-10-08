package pt.diamondcars.catalogbackend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.nimbusds.jose.util.Resource;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link DeadlineResourceRetriever} against a raw socket server that misbehaves on purpose (TASK-002,
 * review r2, S-a). Every limit is checked by how long the call takes and, where it matters, by the
 * server seeing its connection closed: a fetch that gave up but left the connection open would still
 * hold a socket for as long as the server wanted.
 *
 * <p>Limits here are short (0.5 s to the headers, 1 s in total, 1 KB); the production values are
 * pinned in {@link Auth0JwkSourceTest}.
 */
class DeadlineResourceRetrieverTest {

	private static final Duration HEADERS_TIMEOUT = Duration.ofMillis(500);
	private static final Duration DEADLINE = Duration.ofSeconds(1);
	private static final int SIZE_LIMIT = 1024;

	private final DeadlineResourceRetriever retriever = new DeadlineResourceRetriever(HEADERS_TIMEOUT, DEADLINE, SIZE_LIMIT);
	private RawServer server;

	@AfterEach
	void stopServer() throws IOException {
		if (server != null) {
			server.close();
		}
	}

	@Test
	void wellBehavedServerIsRead() throws Exception {
		server = RawServer.answering(out -> out.write(response(200, "{\"keys\":[]}")));

		Resource resource = retriever.retrieveResource(server.url());

		assertThat(resource.getContent()).isEqualTo("{\"keys\":[]}");
		assertThat(resource.getContentType()).isEqualTo("application/json");
	}

	/** Connected, request read, not a byte back: the headers timeout ends it. */
	@Test
	void silentServerFailsAtTheHeadersTimeout() throws Exception {
		server = RawServer.answering(out -> sleep(Duration.ofSeconds(30)));

		Duration took = timeFailure(HttpTimeoutException.class);

		assertThat(took).isBetween(Duration.ofMillis(400), Duration.ofMillis(900));
	}

	/**
	 * Headers one byte at a time, each well inside any per-read timeout. With {@code
	 * HttpURLConnection} not even {@code disconnect()} from another thread stops this (it waits for
	 * the lock the reading thread holds while the headers arrive; measured on JDK 21).
	 */
	@Test
	void headersSentByteByByteFailAtTheHeadersTimeout() throws Exception {
		server = RawServer.answering(out -> drip(out, response(200, "{\"keys\":[]}"), Duration.ofMillis(100)));

		Duration took = timeFailure(HttpTimeoutException.class);

		assertThat(took).isBetween(Duration.ofMillis(400), Duration.ofMillis(900));
		assertThat(server.awaitClosedByClient()).as("connection closed by the client").isTrue();
	}

	/** Headers at once, then the body one byte every 100 ms: only the total deadline stops it. */
	@Test
	void bodySentByteByByteFailsAtTheDeadlineAndClosesTheConnection() throws Exception {
		String head = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 1000\r\n\r\n";
		server =
				RawServer.answering(
						out -> {
							out.write(head.getBytes(StandardCharsets.US_ASCII));
							out.flush();
							drip(out, "x".repeat(1000).getBytes(StandardCharsets.US_ASCII), Duration.ofMillis(100));
						});

		IOException failure = failure();

		assertThat(failure).isInstanceOf(HttpTimeoutException.class).hasMessageContaining("prazo total de 1000 ms");
		assertThat(server.awaitClosedByClient()).as("connection closed by the client").isTrue();
	}

	@Test
	void bodyOverTheSizeLimitIsRefusedAndExactlyTheLimitIsAccepted() throws Exception {
		server = RawServer.answering(out -> out.write(response(200, "x".repeat(SIZE_LIMIT + 1))));
		assertThat(failure()).hasMessage("JWKS maior do que o limite de 1024 bytes");
		server.close();

		server = RawServer.answering(out -> out.write(response(200, "x".repeat(SIZE_LIMIT))));
		assertThat(retriever.retrieveResource(server.url()).getContent()).hasSize(SIZE_LIMIT);
	}

	/** Without {@code Content-Length} the limit still applies, while the body arrives. */
	@Test
	void endlessChunkedBodyIsCutAtTheSizeLimit() throws Exception {
		server =
				RawServer.answering(
						out -> {
							out.write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
							byte[] chunk = ("100\r\n" + "x".repeat(256) + "\r\n").getBytes(StandardCharsets.US_ASCII);
							for (int i = 0; i < 100_000; i++) {
								out.write(chunk);
							}
						});

		Duration took = timeFailure(IOException.class);

		assertThat(took).isLessThan(Duration.ofMillis(500));
		assertThat(failure()).hasMessage("JWKS maior do que o limite de 1024 bytes");
	}

	@Test
	void errorStatusIsAFailure() throws Exception {
		server = RawServer.answering(out -> out.write(response(404, "{\"keys\":[]}")));

		assertThat(failure()).hasMessage("HTTP 404");
	}

	/** Auth0 serves its JWKS directly; a redirect is a failure, and its target is never called. */
	@Test
	void redirectIsNotFollowed() throws Exception {
		try (RawServer target = RawServer.answering(out -> out.write(response(200, "{\"keys\":[]}")))) {
			server =
					RawServer.answering(
							out ->
									out.write(
											("HTTP/1.1 302 Found\r\nLocation: " + target.url() + "\r\nContent-Length: 0\r\n\r\n")
													.getBytes(StandardCharsets.US_ASCII)));

			assertThat(failure()).hasMessage("HTTP 302");
			assertThat(target.connections()).isZero();
		}
	}

	private IOException failure() {
		return assertTimeoutPreemptively(
				Duration.ofSeconds(10),
				() -> {
					try {
						retriever.retrieveResource(server.url());
					} catch (IOException e) {
						return e;
					}
					throw new AssertionError("the fetch should have failed");
				});
	}

	private Duration timeFailure(Class<? extends IOException> type) {
		long start = System.nanoTime();
		assertThat(failure()).isInstanceOf(type);
		return Duration.ofNanos(System.nanoTime() - start);
	}

	private static byte[] response(int status, String body) {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		String head =
				"HTTP/1.1 " + status + " X\r\nContent-Type: application/json\r\nContent-Length: " + bytes.length + "\r\n\r\n";
		byte[] all = new byte[head.length() + bytes.length];
		System.arraycopy(head.getBytes(StandardCharsets.US_ASCII), 0, all, 0, head.length());
		System.arraycopy(bytes, 0, all, head.length(), bytes.length);
		return all;
	}

	private static void drip(OutputStream out, byte[] bytes, Duration interval) throws IOException {
		for (byte b : bytes) {
			out.write(b);
			out.flush();
			sleep(interval);
		}
	}

	private static void sleep(Duration duration) {
		try {
			Thread.sleep(duration);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	@FunctionalInterface
	interface Answer {
		void write(OutputStream out) throws IOException;
	}

	/**
	 * One loopback server socket; each connection reads the request and gets {@code answer}. Records
	 * whether its writes failed because the client closed the connection.
	 */
	private static final class RawServer implements AutoCloseable {

		private final ServerSocket socket;
		private final AtomicInteger connections = new AtomicInteger();
		private final CompletableFuture<Boolean> closedByClient = new CompletableFuture<>();

		private RawServer(Answer answer) throws IOException {
			socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
			Thread.ofVirtual()
					.start(
							() -> {
								while (!socket.isClosed()) {
									try {
										Socket connection = socket.accept();
										connections.incrementAndGet();
										Thread.ofVirtual().start(() -> serve(connection, answer));
									} catch (IOException e) {
										return;
									}
								}
							});
		}

		static RawServer answering(Answer answer) throws IOException {
			return new RawServer(answer);
		}

		URL url() throws IOException {
			return URI.create("http://127.0.0.1:" + socket.getLocalPort() + "/.well-known/jwks.json").toURL();
		}

		int connections() {
			return connections.get();
		}

		/** @return whether a write to the client failed within 3 s (the client closed the connection) */
		boolean awaitClosedByClient() throws Exception {
			try {
				return closedByClient.get(3, TimeUnit.SECONDS);
			} catch (java.util.concurrent.TimeoutException e) {
				return false;
			}
		}

		private void serve(Socket connection, Answer answer) {
			try (connection) {
				InputStream in = connection.getInputStream();
				in.read(new byte[8192]);
				answer.write(connection.getOutputStream());
				connection.getOutputStream().flush();
			} catch (IOException e) {
				closedByClient.complete(true);
			}
		}

		@Override
		public void close() throws IOException {
			socket.close();
		}
	}
}
