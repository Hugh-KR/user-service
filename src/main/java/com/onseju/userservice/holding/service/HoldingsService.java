package com.onseju.userservice.holding.service;

import com.onseju.userservice.holding.domain.Holdings;
import com.onseju.userservice.holding.service.dto.AfterTradeHoldingsDto;
import com.onseju.userservice.holding.service.dto.BeforeTradeHoldingsDto;
import com.onseju.userservice.holding.service.repository.HoldingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class HoldingsService {

	private final HoldingsRepository holdingsRepository;

	@Transactional
	public void updateHoldingsAfterTrade(final AfterTradeHoldingsDto params) {
		optimizeLoop(() -> {
			final Holdings holdings
					= holdingsRepository.getOrDefaultByAccountIdAndCompanyCode(params.accountId(), params.companyCode());
			holdings.updateHoldings(params.type(), params.price(), params.quantity());
			holdingsRepository.save(holdings);
		});
	}

	public void reserve(final BeforeTradeHoldingsDto dto) {
		optimizeLoop(() -> {
			if (dto.type().isSell()) {
				final Holdings holdings
						= holdingsRepository.getByAccountIdAndCompanyCode(dto.accountId(), dto.companyCode());
				holdings.validateExistHoldings();
				holdings.validateEnoughHoldings(dto.totalQuantity());
				holdings.reserveOrder(dto.totalQuantity());
				holdingsRepository.save(holdings);
			}
		});
	}

	private void optimizeLoop(Runnable run) {
		int maxRetries = 5; // 최대 재시도 횟수 설정
		int retryCount = 0;

		while (retryCount < maxRetries) {
			try {
				run.run();
				return; // 성공적으로 실행되면 즉시 반환
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
