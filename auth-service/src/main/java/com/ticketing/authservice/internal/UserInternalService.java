package com.ticketing.authservice.internal;

import com.ticketing.authservice.common.ApiException;
import com.ticketing.authservice.common.ErrorCode;
import com.ticketing.authservice.internal.dto.UserInternalResponse;
import com.ticketing.authservice.notification.NotificationPreferenceService;
import com.ticketing.authservice.user.User;
import com.ticketing.authservice.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class UserInternalService {

    private final UserRepository userRepository;
    private final NotificationPreferenceService notificationPreferenceService;

    public UserInternalService(UserRepository userRepository, NotificationPreferenceService notificationPreferenceService) {
        this.userRepository = userRepository;
        this.notificationPreferenceService = notificationPreferenceService;
    }

    public UserInternalResponse getUser(Long userId) {
        User user = userRepository.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));
        return new UserInternalResponse(
                user.getId(), user.getEmail(), user.getName(), notificationPreferenceService.isEmailOptedIn(userId));
    }
}
