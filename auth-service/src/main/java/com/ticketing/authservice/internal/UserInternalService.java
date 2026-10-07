package com.ticketing.authservice.internal;

import com.ticketing.authservice.common.ApiException;
import com.ticketing.authservice.common.ErrorCode;
import com.ticketing.authservice.internal.dto.UserInternalResponse;
import com.ticketing.authservice.notification.NotificationPreferenceService;
import com.ticketing.authservice.user.User;
import com.ticketing.authservice.user.UserRepository;
import com.ticketing.authservice.user.AuthProvider;
import com.ticketing.authservice.user.Role;
import java.util.Optional;
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

    /**
     * Resolves a transfer recipient by email. An address can exist under several login providers;
     * the local account wins (it is the only one whose email we verified ourselves), then Google,
     * then Kakao. Admin accounts are never a valid recipient.
     */
    public UserInternalResponse lookupByEmail(String email) {
        for (AuthProvider provider : new AuthProvider[] {AuthProvider.LOCAL, AuthProvider.GOOGLE, AuthProvider.KAKAO}) {
            Optional<User> found = userRepository.findByProviderAndEmail(provider, email)
                    .filter(u -> u.getRole() != Role.ADMIN)
                    .filter(u -> provider != AuthProvider.LOCAL || u.isEmailVerified());
            if (found.isPresent()) {
                User user = found.get();
                return new UserInternalResponse(user.getId(), user.getEmail(), user.getName(),
                        notificationPreferenceService.isEmailOptedIn(user.getId()));
            }
        }
        throw new ApiException(ErrorCode.USER_NOT_FOUND);
    }

    public UserInternalResponse getUser(Long userId) {
        User user = userRepository.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));
        return new UserInternalResponse(
                user.getId(), user.getEmail(), user.getName(), notificationPreferenceService.isEmailOptedIn(userId));
    }
}
