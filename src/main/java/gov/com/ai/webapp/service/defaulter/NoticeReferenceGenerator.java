package gov.com.ai.webapp.service.defaulter;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Component
@RequiredArgsConstructor
public class NoticeReferenceGenerator {
    private final JdbcTemplate jdbcTemplate;
    public String nextInternalGstr3aReference() { return reference("GSTR3A"); }
    public String nextInternalAsmt13Reference() { return reference("ASMT13"); }
    private String reference(String type) {
        Long sequence = jdbcTemplate.queryForObject("SELECT SEQ_GST_NOTICE_REF.NEXTVAL FROM DUAL", Long.class);
        if (sequence == null) throw new IllegalStateException("Unable to generate statutory reference");
        return "WB/GST/" + type + "/" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "/" + String.format("%010d", sequence);
    }
}
