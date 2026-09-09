package com.ticketing.backend.auth;

import com.ticketing.backend.common.ApiException;
import com.ticketing.backend.common.ErrorCode;
import org.springframework.security.core.context.SecurityContextHolder;

public final class SecurityUtil {

    private SecurityUtil() {
    }

    public static Long getCurrentUserId() {
        Object principal = SecurityContextHolder.getContext().getAuthentication() == null
                ? null
                : SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (!(principal instanceof Long userId)) {
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }
        return userId;
    }
}
