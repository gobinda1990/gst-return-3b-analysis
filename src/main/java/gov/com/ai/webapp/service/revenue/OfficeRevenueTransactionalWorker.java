package gov.com.ai.webapp.service.revenue;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import gov.com.ai.webapp.exception.RevenueBatchException;
import gov.com.ai.webapp.model.dto.OfficeMonthlyRevenue;
import gov.com.ai.webapp.repository.revenue.OfficeRevenueBatchRepository;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class OfficeRevenueTransactionalWorker {
	private final OfficeRevenueBatchRepository repo;
	private final OfficeRevenueGrowthCalculator calc;

	@Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
	public Result process(String p) {
		List<OfficeMonthlyRevenue> a = repo.aggregate(p);
		if (a.isEmpty())
			throw new RevenueBatchException("No mapped office aggregates for " + p);
		int merged = 0;
		for (var r : a)
			merged += repo.merge(r);
		int stale = repo.deleteStale(p), growth = 0;
		for (String j : repo.offices(p)) {
			var cur = repo.exact(j, p);
			if (cur == null)
				continue;
			var g = calc.calculate(cur, repo.history(j, p));
			int u = repo.updateGrowth(p, j, g.momOutputGrowth(), g.yoyOutputGrowth(), g.momCashGrowth(),
					g.yoyCashGrowth(), g.avgOutputTax3m(), g.avgOutputTax6m(), g.avgOutputTax12m(), g.avgCashTax3m(),
					g.avgCashTax6m(), g.avgCashTax12m(), g.growthTrend(), g.growthStatus());
			if (u != 1)
				throw new RevenueBatchException("Growth update failed for " + j + "/" + p);
			growth += u;
		}
		return new Result(a.size(), merged, stale, growth);
	}

	public record Result(int offices, int merged, int staleDeleted, int growthUpdated) {
	}
}
