package in.xiandan.magnetw.filter;

import org.apache.log4j.Logger;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import in.xiandan.magnetw.handler.RequestLoggerHandler;

/**
 * created 2019/5/5 15:21
 */
public class RequestLoggerFilter extends OncePerRequestFilter {
    private Logger logger = Logger.getLogger(getClass());

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        if (!request.getRequestURI().endsWith("feedback")) {
            logger.info(RequestLoggerHandler.buildRequestString(request));
        }

        chain.doFilter(request, response);
    }


}
