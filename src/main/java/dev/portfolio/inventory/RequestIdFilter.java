package dev.portfolio.inventory;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
public class RequestIdFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String supplied = request.getHeader("X-Request-Id");
        String requestId = supplied != null && supplied.matches("[A-Za-z0-9._:-]{1,100}")
            ? supplied : UUID.randomUUID().toString();
        try {
            MDC.put("requestId", requestId);
            response.setHeader("X-Request-Id", requestId);
            chain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }
}
