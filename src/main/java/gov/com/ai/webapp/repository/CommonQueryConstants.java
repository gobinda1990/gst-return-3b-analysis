package gov.com.ai.webapp.repository;

public final class CommonQueryConstants {

	private CommonQueryConstants() {
	}
	
	public static final String FETCH_PASSWORD=" select passwd from user_master where hrms_code=? ";
	
	public static final String UPDATE_PASSWORD_USER=" update user_master set passwd=?, password_expire_dt=CURRENT_DATE + INTERVAL '90 days' where hrms_code=? ";
	
	public static final String UPDATE_PASSWORD_ADMIN=" update user_master set passwd=?, password_expire_dt=CURRENT_DATE - INTERVAL '1 day' where hrms_code=? ";
	
	public static final String FETCH_SECURITY_QS="select hrms_code,hint_qs_cd,hint_ans from user_master where hrms_code=?";

	// ==========================================================
	
	public static final String FETCH_CIRCLE = "SELECT circle_cd FROM circle_cd";

	public static final String FETCH_CHARGE = "SELECT charge_cd FROM charge_cd";

	public static final String FETCH_OFFICE = "SELECT office_cd FROM office_cd";

	public static final String FETCH_ACTIVE_PROJECTS = "SELECT pro_id FROM project_det";

	public static final String FETCH_CIRCLE_SQL = "SELECT circle_cd, circle_nm FROM circle_cd";

	public static final String FETCH_CHARGE_SQL = "SELECT charge_cd, charge_nm FROM charge_cd";

	public static final String FETCH_OFFICE_SQL = "SELECT office_cd, office_nm FROM office_cd";

	public static final String FETCH_DESIGNATION_SQL = "SELECT desig_cd, designation, hierarchy FROM designation_cd";

	public static final String FETCH_ROLE_SQL = "SELECT role_id,role_name FROM role_master order by role_id asc";

	public static final String FETCH_PROJECT_SQL_USER = "SELECT pro_id, pro_name, pro_url FROM project_det order by pro_id ";

	public static final String FETCH_USER_ASSIGNED_PROJECT = "SELECT pro_id, pro_name, pro_url FROM project_det WHERE pro_id IN (:projectIds) order by pro_id ";

	public static final String SELECT_USER_DET = "SELECT um.hrms_code AS hrmsCode, um.full_name AS fullName, um.email AS email, "
			+ " um.phone_no AS phoneNo,d.designation AS desigName, um.gpf_no AS gpfNo, um.pan_no AS panNo, "
			+ " um.bo_id AS boId,um.gender, upi.img_url AS profileImageUrl, um.joining_order_no AS joiningOrderNo, "
			+ " um.blood_group AS bloodGroup, um.house_no AS houseNo, um.police_station AS policeStation, "
			+ " um.district AS district, um.state_cd AS state, um.pin_code AS pinCode, um.usr_status_cd AS userStatus FROM user_master um "
			+ " LEFT JOIN designation_cd d ON um.desig_cd = d.desig_cd LEFT JOIN user_profile_img upi ON "
			+ " um.hrms_code = upi.hrms_code AND upi.img_up_dt = (SELECT MAX(img_up_dt) FROM "
			+ " user_profile_img WHERE hrms_code = um.hrms_code) AND upi.status = ? WHERE um.hrms_code = ?";

	public static final String UPDATE_PROFILE_SQL = " UPDATE user_master SET joining_order_no = ?, blood_group = ?, email = ?, "
			+ " phone_no = ?, gpf_no = ?, bo_id = ?, house_no = ?, police_station = ?, district = ?, state_cd = ?, "
			+ " pin_code = ? WHERE hrms_code = ?";

	public static final String UPSERT_PROFILE_IMG_URL_SQL = "INSERT INTO user_profile_img (hrms_code, img_url, img_up_dt, status) "
			+ "VALUES (?, ?, CURRENT_TIMESTAMP, ?) " + "ON CONFLICT (hrms_code) " + "DO UPDATE SET "
			+ "img_url = EXCLUDED.img_url, " + "img_up_dt = CURRENT_TIMESTAMP, " + "status = EXCLUDED.status";
	
	public static final String INSERT_OPS_LOG="INSERT INTO ops_audit_log (hrms_code, tab_name, action_type, ip_address) "
			 + " VALUES (?, ?, ?, ?)";

	public static final String SQL_FETCH_USER_OFFICES =" SELECT jsonb_build_object('hrmsCode', upd.hrms_code,'officeType', "
			+ " posting_item->>'officeType','offices', jsonb_agg(jsonb_build_object('officeId', office_item->>'officeId', "
			+ " 'officeName', office_item->>'officeName') ORDER BY office_item->>'officeName') "
			+ " ) AS user_offices FROM acs_mast.user_assign_det upd "
			+ " CROSS JOIN LATERAL jsonb_array_elements(upd.postings) AS posting_item "
			+ " LEFT JOIN LATERAL jsonb_array_elements(posting_item->'offices') AS office_item ON TRUE "
			+ " WHERE upd.status = ? "
			+ " AND upd.hrms_code = ? "
			+ " AND posting_item->>'postingType' = 'M' "
			+ " AND posting_item->>'status' = ? "
			+ " AND office_item->>'status' =? "
			+ " AND EXISTS ( SELECT 1 FROM jsonb_array_elements(posting_item->'modules') AS module_item "
			+ " WHERE module_item->>'projectId' = '1' ) "
			+ " GROUP BY upd.hrms_code, posting_item->>'officeType'";

	public static final String SQL_CHARGE_BY_CIRCLE_TEMPLATE = "SELECT DISTINCT charge_cd FROM charge_cd WHERE circle_cd IN (%s)";

	public static final String INSERT_PROJECT = "INSERT INTO project_det(pro_id, pro_name, pro_url) VALUES "
			+ " (nextval('project_det_seq'), ?, ?)";

	public static final String UPDATE_PROJECT = "update project_det  set pro_name=?, pro_url=? "
			+ " where pro_id =? ";
	
	public static final String API_GATWAY_URL=" select api_gatway from project_det where pro_id=:proId ";

	public static final String REPORT_COUNT_SQL =
		    "SELECT " +
		    "COUNT(DISTINCT CASE " +
		    "    WHEN um.usr_status_cd = 'A' AND uad.hrms_code IS NOT NULL " +
		    "    THEN um.hrms_code END) AS assigned_count, " +
		    "COUNT(DISTINCT CASE " +
		    "    WHEN um.usr_status_cd = 'I' " +
		    "    THEN um.hrms_code END) AS common_pool_count " +
		    "FROM user_master um " +
		    "LEFT JOIN user_assign_det uad " +
		    "ON um.hrms_code = uad.hrms_code";
}
