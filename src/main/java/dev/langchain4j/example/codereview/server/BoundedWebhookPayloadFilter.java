package dev.langchain4j.example.codereview.server;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

final class BoundedWebhookPayloadFilter extends OncePerRequestFilter {

    private final int maxWebhookBytes;

    BoundedWebhookPayloadFilter(int maxWebhookBytes) {
        this.maxWebhookBytes = maxWebhookBytes;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !"/webhooks/github".equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (request.getContentLengthLong() > maxWebhookBytes) {
            payloadTooLarge(response);
            return;
        }
        filterChain.doFilter(new BoundedRequest(request, maxWebhookBytes), response);
    }

    private static void payloadTooLarge(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setContentType("text/plain");
        response.getWriter().write("WEBHOOK_PAYLOAD_TOO_LARGE");
    }

    private static final class BoundedRequest extends HttpServletRequestWrapper {

        private final int maxWebhookBytes;

        private BoundedRequest(HttpServletRequest request, int maxWebhookBytes) {
            super(request);
            this.maxWebhookBytes = maxWebhookBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            return new BoundedServletInputStream(super.getInputStream(), maxWebhookBytes);
        }
    }

    private static final class BoundedServletInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final int maxWebhookBytes;
        private int readBytes;

        private BoundedServletInputStream(ServletInputStream delegate, int maxWebhookBytes) {
            this.delegate = delegate;
            this.maxWebhookBytes = maxWebhookBytes;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value != -1) {
                count(1);
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = delegate.read(bytes, offset, Math.min(length, maxWebhookBytes + 1 - readBytes));
            if (read > 0) {
                count(read);
            }
            return read;
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }

        private void count(int read) throws WebhookPayloadTooLargeException {
            readBytes += read;
            if (readBytes > maxWebhookBytes) {
                throw new WebhookPayloadTooLargeException();
            }
        }
    }
}

final class WebhookPayloadTooLargeException extends IOException {
}
