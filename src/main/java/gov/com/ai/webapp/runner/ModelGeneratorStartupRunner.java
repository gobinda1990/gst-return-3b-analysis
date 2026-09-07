package gov.com.ai.webapp.runner;

import gov.com.ai.webapp.manager.GstAiModelTrainer;
import gov.com.ai.webapp.manager.GstAiModelTrainer.GstDatasetRecord;
import gov.com.ai.webapp.manager.GstAiModelTrainer.ModelMetrics;
import gov.com.ai.webapp.manager.XGBoostModelManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Automates synthetic dataset generation, training, and persistence of
 * XGBoost model binaries if they do not exist on application startup.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ModelGeneratorStartupRunner implements CommandLineRunner {

    private final XGBoostModelManager modelManager;

    @Value("${ai.model.directory:./models}")
    private String modelDirectoryPath;

    @Value("${gst.ai.model.auto-generate:true}")
    private boolean autoGenerateOnMissing;

    @Value("${gst.ai.training.sample-size:10000}")
    private int sampleSize;

    @Value("${gst.ai.training.rounds:50}")
    private int trainingRounds;

    @Override
    public void run(String... args) throws Exception {
        File modelDir = new File(modelDirectoryPath);
        if (!modelDir.exists()) {
            boolean created = modelDir.mkdirs();
            log.info("[AI Auto-Generator] Model directory missing. Created directory [{}] (Success: {})", 
                    modelDir.getAbsolutePath(), created);
        }

        boolean needsGeneration = false;
        for (String modelName : XGBoostModelManager.REQUIRED_MODELS) {
            File modelFile = new File(modelDir, modelName + ".model");
            if (!modelFile.exists() || modelFile.length() == 0) {
                needsGeneration = true;
                log.warn("[AI Auto-Generator] Required model binary missing or empty: [{}]", modelFile.getName());
                break;
            }
        }

        if (needsGeneration && autoGenerateOnMissing) {
            log.info("[AI Auto-Generator] Missing XGBoost binaries detected. Commencing automatic Java model generation pipeline...");
            generateAndPersistAllModels();
            
            log.info("[AI Auto-Generator] Triggering atomic reload sequence in XGBoostModelManager...");
            if (modelManager != null) {
                boolean reloadSuccess = modelManager.reloadModels();
                log.info("[AI Auto-Generator] Reload sequence completed. Fully operational status: {}", reloadSuccess);
            }
        } else if (!needsGeneration) {
            log.info("[AI Auto-Generator] All required XGBoost model binaries are present and non-empty.");
        }
    }

    /**
     * Executes the synthetic dataset generation and multi-target XGBoost training loop.
     */
    public synchronized void generateAndPersistAllModels() {
        log.info("[AI Training Pipeline] Generating synthetic GST Return 3B dataset with {} records...", sampleSize);
        List<GstDatasetRecord> dataset = GstAiModelTrainer.generateDataset(sampleSize);

        List<ModelMetrics> metricsList = new ArrayList<>();
        Map<String, Object> commonParams = getXGBoostHyperparameters();

        try {
            // 1. Output Tax Model
            GstAiModelTrainer.trainAndPersistModel(
                    GstAiModelTrainer.MODEL_NAME_OUTPUT_TAX,
                    dataset,
                    GstDatasetRecord::getTargetOutputTax,
                    commonParams,
                    trainingRounds,
                    modelDirectoryPath,
                    metricsList
            );

            // 2. ITC Model
            GstAiModelTrainer.trainAndPersistModel(
                    GstAiModelTrainer.MODEL_NAME_ITC,
                    dataset,
                    GstDatasetRecord::getTargetITC,
                    commonParams,
                    trainingRounds,
                    modelDirectoryPath,
                    metricsList
            );

            // 3. Cash Tax Model
            GstAiModelTrainer.trainAndPersistModel(
                    GstAiModelTrainer.MODEL_NAME_CASH_TAX,
                    dataset,
                    GstDatasetRecord::getTargetCashTax,
                    commonParams,
                    trainingRounds,
                    modelDirectoryPath,
                    metricsList
            );

            // 4. Filing Delay Model
            GstAiModelTrainer.trainAndPersistModel(
                    GstAiModelTrainer.MODEL_NAME_DELAY,
                    dataset,
                    GstDatasetRecord::getTargetDelayDays,
                    commonParams,
                    trainingRounds,
                    modelDirectoryPath,
                    metricsList
            );

            // 5. Risk Score Model (Binary Classification)
            Map<String, Object> riskParams = new HashMap<>(commonParams);
            riskParams.put("objective", "binary:logistic");
            riskParams.put("eval_metric", "logloss");

            GstAiModelTrainer.trainAndPersistModel(
                    GstAiModelTrainer.MODEL_NAME_RISK,
                    dataset,
                    GstDatasetRecord::getTargetFraudRisk,
                    riskParams,
                    trainingRounds,
                    modelDirectoryPath,
                    metricsList
            );

            log.info("[AI Training Pipeline] Successfully trained and persisted all 5 XGBoost binaries to [{}]", modelDirectoryPath);

            // Export performance metrics markdown report
            GstAiModelTrainer.generateMarkdownReport(metricsList, GstAiModelTrainer.DEFAULT_REPORT_DIR);

        } catch (Exception e) {
            log.error("[AI Training Pipeline] Critical error during model auto-generation", e);
        }
    }

    private Map<String, Object> getXGBoostHyperparameters() {
        Map<String, Object> params = new HashMap<>();
        params.put("eta", 0.1);
        params.put("max_depth", 6);
        params.put("subsample", 0.8);
        params.put("colsample_bytree", 0.8);
        params.put("objective", "reg:squarederror");
        params.put("eval_metric", "rmse");
        params.put("nthread", Runtime.getRuntime().availableProcessors());
        return params;
    }
}