package com.onseju.userservice.account.service;

import com.onseju.userservice.account.domain.Account;
import com.onseju.userservice.account.service.dto.AfterTradeAccountDto;
import com.onseju.userservice.account.service.dto.BeforeTradeAccountDto;
import com.onseju.userservice.account.service.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

@RequiredArgsConstructor
@Service
@Slf4j
public class AccountService {

	private final AccountRepository accountRepository;

	public void updateAccountAfterTrade(final AfterTradeAccountDto dto) {
		optimizeLoop(() -> {
			Account account = accountRepository.getById(dto.accountId());
			account.processOrder(
					dto.type(),
					dto.price(),
					dto.quantity()
			);
			accountRepository.save(account);
			return account.getId();
		});
	}

	public Long reserve(final BeforeTradeAccountDto dto) {
		return optimizeLoop(() -> {
			if (dto.type().isBuy()) {
				Account account = accountRepository.getByMemberId(dto.memberId());
				account.validateDepositBalance(dto.price().multiply(dto.totalQuantity()));
				account.processReservedOrder(dto.price().multiply(dto.totalQuantity()));
				accountRepository.save(account);
				return account.getId();
			}
			return accountRepository.getByMemberId(dto.memberId()).getId();
		});
	}

	private Long optimizeLoop(LongSupplier supplier) {
		int maxRetries = 5; // 최대 재시도 횟수 설정
		int retryCount = 0;

		while (retryCount < maxRetries) {
			try {
				return supplier.getAsLong();
			} catch (ObjectOptimisticLockingFailureException ex) {
				retryCount++;
				if (retryCount >= maxRetries) {
					throw new RuntimeException("최대 재시도 횟수를 초과했습니다: " + maxRetries, ex);
				}

				// 백오프 시간 계산
				long backoffTime = 100 * (long) Math.pow(2, retryCount - 1); // 100ms, 200ms, 400ms, ...
				// 최대 대기 시간 제한
				backoffTime = Math.min(backoffTime, 5000);

				try {
					Thread.sleep(backoffTime);
					log.info("낙관적 락 충돌 발생, {}번째 재시도 ({}ms 후)", retryCount, backoffTime);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new RuntimeException("재시도 중 인터럽트 발생", e);
				}
			}
		}
		throw new RuntimeException("재시도 로직 실패");
	}
}
