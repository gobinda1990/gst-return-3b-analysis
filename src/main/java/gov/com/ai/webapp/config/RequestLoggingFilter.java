package gov.com.ai.webapp.config;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * Puts a request id into the logging MDC (every log line of the request gets
 * it via %X{requestId}) and writes one access-log line per request.
 *
 * Spring Boot 2.x: replace the jakarta.servlet imports with javax.servlet.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class RequestLoggingFilter extends OncePerRequestFilter {

	public static final String REQUEST_ID = "requestId";
	private static final String HEADER = "X-Request-Id";
	private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
	private static final long SLOW_REQUEST_MS = 2_000;

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String uri = request.getRequestURI();
		return uri.startsWith("/actuator") || uri.startsWith("/static") || uri.equals("/favicon.ico");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String incoming = request.getHeader(HEADER);
		// Only accept simple ids - prevents log injection through the header.
		String id = incoming != null && SAFE_ID.matcher(incoming).matches() ? incoming
				: UUID.randomUUID().toString().substring(0, 8);
		MDC.put(REQUEST_ID, id);
		response.setHeader(HEADER, id);

		long start = System.nanoTime();
		try {
			chain.doFilter(request, response);
		} catch (Exception ex) {
			/*
			 * Anything that reaches here was NOT resolved by Spring MVC's
			 * exception handling (@RestControllerAdvice resolves exceptions
			 * inside chain.doFilter, so those never surface here). This is
			 * therefore either a failure in another filter ahead of the
			 * DispatcherServlet, or something like a client-disconnect
			 * IOException mid-response. Log it explicitly with its full
			 * stack trace before rethrowing (behavior is unchanged) -
			 * otherwise the access-log line in `finally` below would report
			 * it using whatever status happens to already be on the
			 * response, which is frequently still 200 since nothing ever
			 * got the chance to set an error status.
			 */
			long ms = (System.nanoTime() - start) / 1_000_000;
			log.error("{} {} -> unhandled {} after {} ms", request.getMethod(), request.getRequestURI(),
					ex.getClass().getSimpleName(), ms, ex);
			throw ex;
		} finally {
			long ms = (System.nanoTime() - start) / 1_000_000;
			int status = response.getStatus();
			if (status >= 500) {
				log.error("{} {} -> {} in {} ms", request.getMethod(), request.getRequestURI(), status, ms);
			} else if (ms >= SLOW_REQUEST_MS || status >= 400) {
				log.warn("{} {} -> {} in {} ms", request.getMethod(), request.getRequestURI(), status, ms);
			} else {
				log.info("{} {} -> {} in {} ms", request.getMethod(), request.getRequestURI(), status, ms);
			}
			MDC.remove(REQUEST_ID);
		}
	}
}