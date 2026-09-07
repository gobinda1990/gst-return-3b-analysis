package gov.com.ai.webapp.controller;

import gov.com.ai.webapp.model.GstMlDto.PredictionRequest;
import gov.com.ai.webapp.model.GstMlDto.PredictionResponse;
import gov.com.ai.webapp.service.GstPredictionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/gst")
@CrossOrigin(origins = "*")
@Slf4j
@RequiredArgsConstructor
public class PredictionController {

    private final GstPredictionService predictionService;

    @PostMapping("/analytics/return-3b/predict")
    public ResponseEntity<PredictionResponse> predictRiskAndForecast(@RequestBody PredictionRequest request) {
        log.info("Received ML inference request for GSTIN: [{}]", request.getGstin());
        
        PredictionResponse response = predictionService.predictNextMonth(request);
        return ResponseEntity.ok(response);
    }
}