package gov.com.ai.webapp.repository;

public final class QueryConstants {

	private QueryConstants() {
		// Enforce non-instantiability
	}
	
	public static final String FETCH_RISK_SUMMARY=" SELECT GSTIN, RET_PERIOD,COALESCE(TAXABLE_VALUE, 0) AS TAXABLE_VALUE, "
			+ " COALESCE(TOTAL_OUTPUT_TAX, 0) AS TOTAL_OUTPUT_TAX, COALESCE(ELIGIBLE_ITC, 0) AS ELIGIBLE_ITC, "
			+ " COALESCE(UTILIZED_ITC, 0) AS UTILIZED_ITC,COALESCE(EXCESS_ITC, 0) AS EXCESS_ITC, "
			+ " COALESCE(CASH_TAX_PAID, 0) AS CASH_TAX_PAID,COALESCE(ITC_TO_TAX_RATIO, 0) AS ITC_UTILIZATION_RATIO, "
			+ " COALESCE(CASH_PAYMENT_RATIO, 0) AS CASH_PAYMENT_RATIO, COALESCE(FILING_DELAY_DAYS, 0) AS FILING_DELAY_DAYS, "
			+ " COALESCE(XGB_RISK_SCORE, 0) AS XGB_RISK_SCORE,  DL4J_ANOMALY_SCORE, "
			+ " CASE  WHEN XGB_RISK_SCORE >= 0.85 OR EXCESS_ITC > 100000 THEN 'CRITICAL' "
			+ " WHEN XGB_RISK_SCORE >= 0.65 THEN 'HIGH'   ELSE 'MEDIUM' "
			+ " END AS RISK_CATEGORY FROM gst_ret_3b_summary WHERE RET_PERIOD=?  ORDER BY XGB_RISK_SCORE DESC, EXCESS_ITC DESC";
	
	public static final String FETCH_RETURN_PERIOD=" select distinct ret_period from gst_ret_3b_summary order by "
			+ " to_date(ret_period,'MMYYYY') desc ";
	
	public static final String SQL_SELECT_DEALER = " SELECT gstin, legal_name, trade_name, pan_no, st_juri, ct_juri, "
			+ " auth_status	FROM GST_DEALER_MASTER_WBCOMTAX	WHERE gstin = ? ";
	
	

	public static final String FIND_BY_GSTIN_PERIOD = " SELECT * FROM GST_RET_3B_SUMMARY WHERE GSTIN = ? AND RET_PERIOD = ? ";

	public static final String FIND_HISTORY = " SELECT * FROM GST_RET_3B_SUMMARY WHERE GSTIN = ? ORDER BY "
			+ " TO_DATE(RET_PERIOD, 'MMYYYY') DESC ";

	public static final String FIND_HISTORY_LIMIT = " SELECT * FROM (SELECT * FROM GST_RET_3B_SUMMARY "
			+ " WHERE GSTIN = ?  ORDER BY TO_DATE(RET_PERIOD, 'MMYYYY') DESC) WHERE ROWNUM <= ? ";

	public static final String FIND_LATEST = "SELECT * FROM ( SELECT * FROM GST_RET_3B_SUMMARY "
			+ "  WHERE GSTIN = ?  ORDER BY TO_DATE(RET_PERIOD, 'MMYYYY') DESC) WHERE ROWNUM = 1";

	public static final String FIND_ALL = "SELECT * FROM GST_RET_3B_SUMMARY ORDER BY GSTIN, RET_PERIOD ";
}