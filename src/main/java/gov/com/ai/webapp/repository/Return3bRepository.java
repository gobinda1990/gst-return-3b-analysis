package gov.com.ai.webapp.repository;

import java.util.List;
import java.util.Optional;
import gov.com.ai.webapp.model.DealerMaster;
import gov.com.ai.webapp.model.GstRiskSummaryDto;
import gov.com.ai.webapp.model.Return3BSummaryBean;
import gov.com.ai.webapp.model.ReturnPeriodOptionDto;

public interface Return3bRepository {

	public Optional<DealerMaster> findByGstin(String gstin);

	public List<Return3BSummaryBean> findHistory(String gstin, int monthsLookback);

	public Optional<Return3BSummaryBean> findByGstinAndRetPeriod(String gstin, String retPeriod);

	List<ReturnPeriodOptionDto> getAllAvailableReturnPeriods();

	List<GstRiskSummaryDto> getRiskSummary(String retPeriod);

}
