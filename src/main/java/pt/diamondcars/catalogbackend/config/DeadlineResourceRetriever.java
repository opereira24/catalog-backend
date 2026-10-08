package pt.diamondcars.catalogbackend.config;

import com.nimbusds.jose.util.Resource;
import com.nimbusds.jose.util.ResourceRetriever;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.ResponseInfo;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Fetches the Auth0 JWKS with a hard limit on the whole exchange, not only on each read (TASK-002,
 * review r2, S-a).
 *
 * <p>Nimbus' {@code DefaultResourceRetriever} uses {@code HttpURLConnection}, whose read timeout is
 * per read: a server that sends one byte every 2 s kept a fetch going for more than 25 s (measured
 * in review r2), and while a fetch is in flight no other one starts. Calling {@code disconnect()}
 * from another thread does not help either: on JDK 21 it waits for the same lock as a response whose
 * headers are still arriving (measured: headers sent one byte every 0.5 s, {@code disconnect()} did
 * not return). The JDK {@link HttpClient} has both limits this needs:
 *
 * <ul>
 *   <li>{@code headersTimeout}, the request timeout: connection and response headers (measured: it
 *       includes the connect, a non-routable address fails at the timeout);
 *   <li>{@code deadline}, the whole fetch, body included: when it passes the exchange is cancelled,
 *       which closes the connection (measured: the server sees the reset on its next write).
 * </ul>
 *
 * <p>The body is limited to {@code sizeLimitBytes} while it arrives (the subscription is cancelled
 * at the first byte over the limit), only a {@code 2xx} answer is accepted, and redirects are not
 * followed: Auth0 serves its JWKS directly, and the URL comes from {@code AUTH0_ISSUER_URI}.
 */
final class DeadlineResourceRetriever implements ResourceRetriever {

	private final HttpClient client =
			HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER).build();
	private final Duration headersTimeout;
	private final Duration deadline;
	private final int sizeLimitBytes;

	/**
	 * Creates a retriever.
	 *
	 * @param headersTimeout limit for connecting and receiving the response headers
	 * @param deadline limit for the whole fetch, from the request to the last byte of the body
	 * @param sizeLimitBytes largest body accepted
	 */
	DeadlineResourceRetriever(Duration headersTimeout, Duration deadline, int sizeLimitBytes) {
		this.headersTimeout = headersTimeout;
		this.deadline = deadline;
		this.sizeLimitBytes = sizeLimitBytes;
	}

	/**
	 * GETs {@code url} within the limits.
	 *
	 * @param url the JWKS URL
	 * @return the body (UTF-8) and its content type
	 * @throws IOException on any failure: connection, timeout, deadline, non-{@code 2xx} status,
	 *     body over the size limit, or interruption
	 */
	@Override
	public Resource retrieveResource(URL url) throws IOException {
		HttpRequest request;
		try {
			request = HttpRequest.newBuilder(url.toURI()).timeout(headersTimeout).header("Accept", "application/json").GET().build();
		} catch (URISyntaxException | IllegalArgumentException e) {
			throw new IOException("URL do JWKS invalido: " + url, e);
		}
		CompletableFuture<HttpResponse<byte[]>> exchange = client.sendAsync(request, this::body);
		try {
			HttpResponse<byte[]> response = exchange.get(deadline.toNanos(), TimeUnit.NANOSECONDS);
			return new Resource(
					new String(response.body(), StandardCharsets.UTF_8), response.headers().firstValue("Content-Type").orElse(null));
		} catch (TimeoutException e) {
			throw new HttpTimeoutException("JWKS sem resposta completa dentro do prazo total de " + deadline.toMillis() + " ms");
		} catch (ExecutionException e) {
			throw asIOException(e.getCause());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new InterruptedIOException("Pedido do JWKS interrompido");
		} finally {
			// no-op once the exchange completed; otherwise aborts it and closes the connection
			exchange.cancel(true);
		}
	}

	private BodySubscriber<byte[]> body(ResponseInfo info) {
		int status = info.statusCode();
		return new LimitedBody(sizeLimitBytes, status >= 200 && status <= 299 ? null : new IOException("HTTP " + status));
	}

	private static IOException asIOException(Throwable failure) {
		Throwable cause = failure;
		while (cause instanceof CompletionException && cause.getCause() != null) {
			cause = cause.getCause();
		}
		return cause instanceof IOException io ? io : new IOException(cause);
	}

	/**
	 * Collects the body up to {@code limit} bytes; cancels the subscription (and with it the
	 * connection) at the first byte over it, or at once when the status was already rejected.
	 */
	private static final class LimitedBody implements BodySubscriber<byte[]> {

		private final int limit;
		private final IOException rejection;
		private final CompletableFuture<byte[]> result = new CompletableFuture<>();
		private final ByteArrayOutputStream received = new ByteArrayOutputStream();
		private Flow.Subscription subscription;

		LimitedBody(int limit, IOException rejection) {
			this.limit = limit;
			this.rejection = rejection;
		}

		@Override
		public void onSubscribe(Flow.Subscription subscription) {
			this.subscription = subscription;
			if (rejection != null) {
				fail(rejection);
				return;
			}
			subscription.request(1);
		}

		@Override
		public void onNext(List<ByteBuffer> items) {
			if (result.isDone()) {
				return;
			}
			for (ByteBuffer item : items) {
				if (received.size() + item.remaining() > limit) {
					fail(new IOException("JWKS maior do que o limite de " + limit + " bytes"));
					return;
				}
				byte[] chunk = new byte[item.remaining()];
				item.get(chunk);
				received.writeBytes(chunk);
			}
			subscription.request(1);
		}

		@Override
		public void onError(Throwable throwable) {
			result.completeExceptionally(throwable);
		}

		@Override
		public void onComplete() {
			result.complete(received.toByteArray());
		}

		@Override
		public CompletionStage<byte[]> getBody() {
			return result;
		}

		private void fail(IOException failure) {
			subscription.cancel();
			result.completeExceptionally(failure);
		}
	}
}
