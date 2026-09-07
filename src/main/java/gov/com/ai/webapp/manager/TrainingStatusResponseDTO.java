//package gov.com.ai.webapp.manager;
//
//import gov.com.webapp.ai.service.ModelTrainerService;
//import gov.com.webapp.ai.service.ModelTrainerService.State; // Import service State directly
//import gov.com.webapp.ai.trainer.GstAiModelTrainer.ModelMetrics;
//import lombok.AllArgsConstructor;
//import lombok.Builder;
//import lombok.Data;
//import lombok.NoArgsConstructor;
//import java.io.Serializable;
//import java.time.Duration;
//import java.time.LocalDateTime;
//import java.util.List;
//
//@Data
//@Builder
//@NoArgsConstructor
//@AllArgsConstructor
//public class TrainingStatusResponseDTO implements Serializable {
//	private static final long serialVersionUID = 1L;
//
//	private State state; // Now uses ModelTrainerService.State
//	private String currentStep;
//	private int progressPercent;
//	private LocalDateTime startTime;
//	private LocalDateTime endTime;
//	private Long elapsedTimeSeconds;
//	private String errorMessage;
//	private List<ModelMetrics> metricsSummary;
//	private boolean isTraining;
//
//	public static TrainingStatusResponseDTO fromStatus(ModelTrainerService.TrainingJobStatus status,
//			boolean isCurrentlyTraining) {
//
//		Long seconds = null;
//		if (status.getStartTime() != null) {
//			LocalDateTime endOrNow = status.getEndTime() != null ? status.getEndTime() : LocalDateTime.now();
//			seconds = Duration.between(status.getStartTime(), endOrNow).getSeconds();
//		}
//
//		return TrainingStatusResponseDTO.builder()
//				.state(status.getState()) // Direct assignment works seamlessly
//				.currentStep(status.getCurrentStep())
//				.progressPercent(status.getProgressPercent())
//				.startTime(status.getStartTime())
//				.endTime(status.getEndTime())
//				.elapsedTimeSeconds(seconds)
//				.errorMessage(status.getErrorMessage())
//				.metricsSummary(status.getMetricsSummary())
//				.isTraining(isCurrentlyTraining)
//				.build();
//	}
//}