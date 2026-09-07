package gov.com.ai.webapp.model;

public class DealerMaster {
	private String gstin;
	private String legalName;
	private String tradeName;
	private String panNo;
	private String stJuri;
	private String ctJuri;
	private String authStatus;

	// Getters and Setters
	public String getGstin() {
		return gstin;
	}

	public void setGstin(String gstin) {
		this.gstin = gstin;
	}

	public String getLegalName() {
		return legalName;
	}

	public void setLegalName(String legalName) {
		this.legalName = legalName;
	}

	public String getTradeName() {
		return tradeName;
	}

	public void setTradeName(String tradeName) {
		this.tradeName = tradeName;
	}

	public String getPanNo() {
		return panNo;
	}

	public void setPanNo(String panNo) {
		this.panNo = panNo;
	}

	public String getStJuri() {
		return stJuri;
	}

	public void setStJuri(String stJuri) {
		this.stJuri = stJuri;
	}

	public String getCtJuri() {
		return ctJuri;
	}

	public void setCtJuri(String ctJuri) {
		this.ctJuri = ctJuri;
	}

	public String getAuthStatus() {
		return authStatus;
	}

	public void setAuthStatus(String authStatus) {
		this.authStatus = authStatus;
	}
}