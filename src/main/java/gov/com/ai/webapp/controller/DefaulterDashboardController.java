package gov.com.ai.webapp.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import gov.com.ai.webapp.service.revenue.DefaulterDashboardService;
import gov.com.ai.webapp.service.revenue.GstDefaulterHistoryService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import gov.com.ai.webapp.model.revenue.*;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/gst/return-3b/defaulters")
@RequiredArgsConstructor
public class DefaulterDashboardController {

	private final DefaulterDashboardService service;

	private final GstDefaulterHistoryService historyService;

	@GetMapping("/summary")
	public DefaulterDashboardSummary summary(@RequestParam String retPeriod,
			@RequestParam(required = false) String office, @RequestParam(required = false) String filingStatus,
			@RequestParam(required = false) String riskLevel, @RequestParam(required = false) String defaultLevel,
			@RequestParam(required = false) String gstr3aEligible, @RequestParam(required = false) String search) {

		log.info("ret" + retPeriod);
		return service.summary(
				filter(retPeriod, office, filingStatus, riskLevel, defaultLevel, gstr3aEligible, search, 0, 25));
	}

	@GetMapping("/list")
	public PageResponse<DefaulterDashboardRow> list(@RequestParam String retPeriod,
			@RequestParam(required = false) String office, @RequestParam(required = false) String filingStatus,
			@RequestParam(required = false) String riskLevel, @RequestParam(required = false) String defaultLevel,
			@RequestParam(required = false) String gstr3aEligible, @RequestParam(required = false) String search,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {

		return service.page(
				filter(retPeriod, office, filingStatus, riskLevel, defaultLevel, gstr3aEligible, search, page, size));
	}

	@GetMapping("/periods")
	public List<OptionDto> periods() {
		return service.periods();
	}

	@GetMapping("/offices")
	public List<OptionDto> offices(@RequestParam String retPeriod) {

		return service.offices(retPeriod);
	}

	@GetMapping("/{gstin}/history")
	public ResponseEntity<List<DefaulterHistoryResponse>> getHistory(

			@PathVariable @NotBlank String gstin,

			@RequestParam(defaultValue = "12") @Min(1) @Max(60) Integer months) {

		log.info("GET /gst/return-3b/defaulters/{}/history months={}", gstin, months);

		List<DefaulterHistoryResponse> history = historyService.getHistory(gstin, months);

		return ResponseEntity.ok(history);
	}

	@GetMapping(value = "/export", produces = "text/csv")
	public ResponseEntity<StreamingResponseBody> export(@RequestParam String retPeriod,
			@RequestParam(required = false) String office, @RequestParam(required = false) String filingStatus,
			@RequestParam(required = false) String riskLevel, @RequestParam(required = false) String defaultLevel,
			@RequestParam(required = false) String gstr3aEligible, @RequestParam(required = false) String search) {

		// Pass Integer.MAX_VALUE (or null) to ensure full export, not just 100 rows
		DefaulterDashboardFilter filter = filter(retPeriod, office, filingStatus, riskLevel, defaultLevel,
				gstr3aEligible, search, 0, Integer.MAX_VALUE);

		StreamingResponseBody body = outputStream -> {
			try (BufferedWriter writer = new BufferedWriter(
					new OutputStreamWriter(outputStream, StandardCharsets.UTF_8), 64 * 1024)) {

				// UTF-8 BOM for Microsoft Excel compatibility
				writer.write('\uFEFF');

				service.export(filter, writer);
				writer.flush();
			}
		};

		String filename = "gst-3b-defaulters-" + retPeriod + ".csv";

		return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
				.contentType(MediaType.parseMediaType("text/csv;charset=UTF-8")).body(body);
	}

	private DefaulterDashboardFilter filter(String retPeriod, String office, String filingStatus, String riskLevel,
			String defaultLevel, String gstr3aEligible, String search, int page, int size) {

		return new DefaulterDashboardFilter(retPeriod, office, filingStatus, riskLevel, defaultLevel, gstr3aEligible,
				search, page, size);
	}
}
