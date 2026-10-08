package gov.com.ai.webapp.model;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import java.util.Collection;

public class CustomUserDetails implements UserDetails {

	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	private String hrmsCode;
	private String fullName;
	private String circleCd;
	private String chargeCd;
	private String email;
	private String phoneNo;
	private String gpfNo;
	private String panNo;
	private String boId;

	private Collection<? extends GrantedAuthority> authorities;

	public String getPhoneNo() {
		return phoneNo;
	}

	public void setPhoneNo(String phoneNo) {
		this.phoneNo = phoneNo;
	}

	public String getGpfNo() {
		return gpfNo;
	}

	public void setGpfNo(String gpfNo) {
		this.gpfNo = gpfNo;
	}

	public String getPanNo() {
		return panNo;
	}

	public void setPanNo(String panNo) {
		this.panNo = panNo;
	}

	public String getBoId() {
		return boId;
	}

	public void setBoId(String boId) {
		this.boId = boId;
	}

	

	public String getHrmsCode() {
		return hrmsCode;
	}

	public void setHrmsCode(String hrmsCode) {
		this.hrmsCode = hrmsCode;
	}

	public String getCircleCd() {
		return circleCd;
	}

	public void setCircleCd(String circleCd) {
		this.circleCd = circleCd;
	}

	public String getChargeCd() {
		return chargeCd;
	}

	public void setChargeCd(String chargeCd) {
		this.chargeCd = chargeCd;
	}

	public String getEmail() {
		return email;
	}

	public void setEmail(String email) {
		this.email = email;
	}

	@Override
	public Collection<? extends GrantedAuthority> getAuthorities() {
		return authorities;
	}

	public void setAuthorities(Collection<? extends GrantedAuthority> authorities) {
		this.authorities = authorities;
	}

	// Required by UserDetails
	@Override
	public String getPassword() {
		return null;
	}

	@Override
	public String getUsername() {
		return hrmsCode;
	}

	@Override
	public boolean isAccountNonExpired() {
		return true;
	}

	@Override
	public boolean isAccountNonLocked() {
		return true;
	}

	@Override
	public boolean isCredentialsNonExpired() {
		return true;
	}

	@Override
	public boolean isEnabled() {
		return true;
	}

	public String getFullName() {
		return fullName;
	}

	public void setFullName(String fullName) {
		this.fullName = fullName;
	}
	
	
}
