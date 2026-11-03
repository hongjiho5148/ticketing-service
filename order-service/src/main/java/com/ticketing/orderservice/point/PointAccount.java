package com.ticketing.orderservice.point;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "point_account")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PointAccount {

    @Id
    @Column(name = "user_id")
    private Long userId;

    // PointAccountRepository의 원자적 UPDATE로만 바꾸고 필드를 직접 설정하지 않는다.
    @Column(nullable = false)
    private Long balance;

    public PointAccount(Long userId) {
        this.userId = userId;
        this.balance = 0L;
    }
}
