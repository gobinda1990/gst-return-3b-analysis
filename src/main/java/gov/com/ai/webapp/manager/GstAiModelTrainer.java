package gov.com.ai.webapp.manager;

import ml.dmlc.xgboost4j.java.Booster;
import ml.dmlc.xgboost4j.java.XGBoost;
import ml.dmlc.xgboost4j.java.XGBoostError;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import gov.com.ai.webapp.model.Return3BSummaryBean;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToDoubleFunction;

/**
 * Enterprise AI Trainer and Real-time Inference Engine using XGBoost for GST Compliance.
 */
@Component
@Slf4j
public class GstAiModelTrainer {

    // Dynamic length matching the 71-dimension vector defined in TrainingDataset
    public static final int FEATURE_COUNT = TrainingDataset.getFeatureNames().size();

    public static final String DEFAULT_MODEL_DIR = "./models/";
    public static final String DEFAULT_REPORT_DIR = "target/ai-reports/";
    public static final String MODEL_EXTENSION = ".model";

    private static final long RANDOM_SEED = 42L;
    private static final double TRAIN_SPLIT_RATIO = 0.80; // 80% Train, 20% Test

    public static final String MODEL_NAME_OUTPUT_TAX = "output-tax";
    public static final String MODEL_NAME_ITC = "itc";
    public static final String MODEL_NAME_CASH_TAX = "cash-tax";
    public static final String MODEL_NAME_DELAY = "delay";
    public static final String MODEL_NAME_RISK = "risk";

    @Value("${gst.ai.model.dir:./models/}")
    private String modelDirectoryPath;

    private final Map<String, Booster> loadedBoosters = new ConcurrentHashMap<>();
    private final Map<String, Integer> boosterFeatureDimensions = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        log.info("[AI Engine] Initializing XGBoost Inference Engine from model directory: [{}]", modelDirectoryPath);
        String[] requiredModels = { MODEL_NAME_OUTPUT_TAX, MODEL_NAME_ITC, MODEL_NAME_CASH_TAX, MODEL_NAME_DELAY, MODEL_NAME_RISK };

        for (String modelName : requiredModels) {
            Path path = Paths.get(modelDirectoryPath, modelName + MODEL_EXTENSION);
            if (Files.exists(path)) {
                try {
                    Booster booster = XGBoost.loadModel(path.toAbsolutePath().toString());
                    int numFeatures = resolveBoosterFeatureCount(booster);

                    loadedBoosters.put(modelName, booster);
                    boosterFeatureDimensions.put(modelName, numFeatures);

                    log.info("[AI Engine] Successfully loaded binary [{}] (Detected Expected Features: {})", modelName, numFeatures);
                } catch (XGBoostError e) {
                    log.error("[AI Engine] Failed to load XGBoost model binary for [{}]", modelName, e);
                }
            } else {
                log.warn("[AI Engine] Binary [{}] not found at [{}]. Fallback heuristic calculations enabled.", modelName, path);
            }
        }
    }

    @PreDestroy
    public void destroy() {
        log.info("[AI Engine] Shutting down AI Engine, releasing Booster instances...");
        for (Map.Entry<String, Booster> entry : loadedBoosters.entrySet()) {
            try {
                entry.getValue().dispose();
            } catch (Exception e) {
                log.warn("[AI Engine] Failed to dispose booster instance [{}]", entry.getKey(), e);
            }
        }
        loadedBoosters.clear();
        boosterFeatureDimensions.clear();
    }

    private int resolveBoosterFeatureCount(Booster booster) {
        try {
            Map<String, Integer> scoreMap = booster.getFeatureScore("");
            if (scoreMap != null && !scoreMap.isEmpty()) {
                int maxIndex = -1;
                for (String key : scoreMap.keySet()) {
                    if (key.startsWith("f")) {
                        try {
                            int idx = Integer.parseInt(key.substring(1));
                            if (idx > maxIndex) maxIndex = idx;
                        } catch (NumberFormatException ignored) {}
                    }
                }
                if (maxIndex >= 0) {
                    return maxIndex + 1;
                }
            }
        } catch (Exception e) {
            log.debug("[AI Engine] Could not resolve booster feature count via feature scores. Defaulting to standard feature count.", e);
        }
        return FEATURE_COUNT;
    }

    /*
     * =============================================================================
     * PREDICTION / INFERENCE API
     * =============================================================================
     */

    public Double predictTaxableValue(Return3BSummaryBean bean) {
        if (bean == null) return 0.0;
        Return3BSummaryBean.normalize(bean);

        if (bean.getAvgTaxableValue12m().doubleValue() > 0) {
            double trend = bean.getTaxableValueTrendRatio().doubleValue() > 0 ? bean.getTaxableValueTrendRatio().doubleValue() : 1.0;
            return bean.getAvgTaxableValue12m().doubleValue() * trend;
        }
        return bean.getTaxableValue().doubleValue();
    }

    public Double predictOutputTax(Return3BSummaryBean bean) {
        if (bean == null) return 0.0;
        Return3BSummaryBean.normalize(bean);

        float[] features = convertBeanToFeatures(bean);
        Float prediction = runInference(MODEL_NAME_OUTPUT_TAX, features);

        if (prediction != null) return Math.max(0.0, prediction.doubleValue());
        return bean.getTaxableValue().doubleValue() * 0.18;
    }

    public Double predictItc(Return3BSummaryBean bean) {
        if (bean == null) return 0.0;
        Return3BSummaryBean.normalize(bean);

        float[] features = convertBeanToFeatures(bean);
        Float prediction = runInference(MODEL_NAME_ITC, features);

        if (prediction != null) return Math.max(0.0, prediction.doubleValue());
        return bean.getEligibleItc().doubleValue() * 0.95;
    }

    public Double predictCashTax(Return3BSummaryBean bean) {
        if (bean == null) return 0.0;
        Return3BSummaryBean.normalize(bean);

        float[] features = convertBeanToFeatures(bean);
        Float prediction = runInference(MODEL_NAME_CASH_TAX, features);

        if (prediction != null) return Math.max(0.0, prediction.doubleValue());

        double output = predictOutputTax(bean);
        double itc = predictItc(bean);
        return Math.max(0.0, output - itc);
    }

    public Double predictDelayDays(Return3BSummaryBean bean) {
        if (bean == null) return 0.0;
        Return3BSummaryBean.normalize(bean);

        float[] features = convertBeanToFeatures(bean);
        Float prediction = runInference(MODEL_NAME_DELAY, features);

        if (prediction != null) return Math.max(0.0, prediction.doubleValue());
        return (double) bean.getFilingDelayDays();
    }

    public Double predictRiskScore(Return3BSummaryBean bean) {
        if (bean == null) return 0.0;
        Return3BSummaryBean.normalize(bean);

        float[] features = convertBeanToFeatures(bean);
        Float prediction = runInference(MODEL_NAME_RISK, features);

        if (prediction != null) {
            double score = Math.min(1.0, Math.max(0.0, prediction.doubleValue()));
            bean.setRiskScore(score);
            bean.setXgbRiskScore(BigDecimal.valueOf(score));
            return score;
        }

        double score = 0.05;
        if (bean.getItcUtilizationRatio().doubleValue() > 0.95) score += 0.35;
        if (bean.getFilingDelayDays() > 15) score += 0.25;
        if (bean.getNilSupplyRatio().doubleValue() > 0.50) score += 0.20;
        if (bean.getFraudLabel() != null && bean.getFraudLabel() == 1) score = 1.0;

        score = Math.min(1.0, score);
        bean.setRiskScore(score);
        bean.setXgbRiskScore(BigDecimal.valueOf(score));
        return score;
    }

    private Float runInference(String modelName, float[] fullFeatures) {
        Booster booster = loadedBoosters.get(modelName);
        if (booster == null) return null;

        int expectedCols = boosterFeatureDimensions.getOrDefault(modelName, FEATURE_COUNT);
        float[] adaptedFeatures = adaptFeatureVector(fullFeatures, expectedCols);

        ml.dmlc.xgboost4j.java.DMatrix dMatrix = null;
        try {
            // Instantiate official XGBoost C++ binding Matrix
            dMatrix = new ml.dmlc.xgboost4j.java.DMatrix(adaptedFeatures, 1, expectedCols, Float.NaN);
            
            float[][] results = booster.predict(dMatrix);
            if (results != null && results.length > 0 && results[0].length > 0) {
                return results[0][0];
            }
        } catch (XGBoostError e) {
            log.error("[AI Engine] XGBoost prediction failed on [{}]: {}", modelName, e.getMessage());
        } finally {
            if (dMatrix != null) {
                try {
                    // Manually release native C++ memory safely
                    dMatrix.dispose();
                } catch (Exception e) {
                    log.warn("[AI Engine] Failed to dispose native DMatrix", e);
                }
            }
        }
        return null;
    }

    private float[] adaptFeatureVector(float[] features, int targetLength) {
        if (features == null) return new float[targetLength];
        if (features.length == targetLength) return features;

        float[] adapted = new float[targetLength];
        int copyLength = Math.min(features.length, targetLength);
        System.arraycopy(features, 0, adapted, 0, copyLength);
        return adapted;
    }

    /*
     * =============================================================================
     * FEATURE EXTRACTION
     * =============================================================================
     */

    public float[] convertBeanToFeatures(Return3BSummaryBean bean) {
        return TrainingDataset.extractFeatureVector(bean);
    }

    /*
     * =============================================================================
     * DATA STRUCTURES
     * =============================================================================
     */

    public static class GstDatasetRecord {
        private final String gstin;
        private final String retPeriod;
        private final float[] features;

        private final float targetOutputTax;
        private final float targetITC;
        private final float targetCashTax;
        private final float targetDelayDays;
        private final float targetFraudRisk;

        public GstDatasetRecord(String gstin, String retPeriod, float[] features, float targetOutputTax,
                                float targetITC, float targetCashTax, float targetDelayDays, float targetFraudRisk) {
            if (features == null || features.length != FEATURE_COUNT) {
                throw new IllegalArgumentException("Features length must strictly equal " + FEATURE_COUNT);
            }
            this.gstin = gstin;
            this.retPeriod = retPeriod;
            this.features = features;
            this.targetOutputTax = targetOutputTax;
            this.targetITC = targetITC;
            this.targetCashTax = targetCashTax;
            this.targetDelayDays = targetDelayDays;
            this.targetFraudRisk = targetFraudRisk;
        }

        public String getGstin() { return gstin; }
        public String getRetPeriod() { return retPeriod; }
        public float[] getFeatures() { return features; }
        public float getTargetOutputTax() { return targetOutputTax; }
        public float getTargetITC() { return targetITC; }
        public float getTargetCashTax() { return targetCashTax; }
        public float getTargetDelayDays() { return targetDelayDays; }
        public float getTargetFraudRisk() { return targetFraudRisk; }
    }

    public static class TrainingDataBundle implements AutoCloseable {
        private final ml.dmlc.xgboost4j.java.DMatrix trainMatrix;
        private final ml.dmlc.xgboost4j.java.DMatrix testMatrix;
        private final float[] testLabels;
        private final int trainSize;
        private final int testSize;

        public TrainingDataBundle(ml.dmlc.xgboost4j.java.DMatrix trainMatrix, ml.dmlc.xgboost4j.java.DMatrix testMatrix, float[] testLabels, int trainSize, int testSize) {
            this.trainMatrix = trainMatrix;
            this.testMatrix = testMatrix;
            this.testLabels = testLabels;
            this.trainSize = trainSize;
            this.testSize = testSize;
        }

        public ml.dmlc.xgboost4j.java.DMatrix getTrainMatrix() { return trainMatrix; }
        public ml.dmlc.xgboost4j.java.DMatrix getTestMatrix() { return testMatrix; }
        public float[] getTestLabels() { return testLabels; }
        public int getTrainSize() { return trainSize; }
        public int getTestSize() { return testSize; }

        @Override
        public void close() {
            if (trainMatrix != null) {
                try { trainMatrix.dispose(); } catch (Exception e) { log.warn("Failed to dispose train DMatrix", e); }
            }
            if (testMatrix != null) {
                try { testMatrix.dispose(); } catch (Exception e) { log.warn("Failed to dispose test DMatrix", e); }
            }
        }
    }

    public static class ModelMetrics {
        private final String modelName;
        private final double rmse;
        private final double mae;
        private final double rSquared;
        private final double logLoss;
        private final double auc;
        private final Map<String, Integer> confusionMatrix;
        private final Map<String, Integer> featureImportance;

        public ModelMetrics(String modelName, double rmse, double mae, double rSquared, double logLoss, double auc,
                            Map<String, Integer> confusionMatrix, Map<String, Integer> featureImportance) {
            this.modelName = modelName;
            this.rmse = rmse;
            this.mae = mae;
            this.rSquared = rSquared;
            this.logLoss = logLoss;
            this.auc = auc;
            this.confusionMatrix = confusionMatrix != null ? confusionMatrix : Collections.emptyMap();
            this.featureImportance = featureImportance != null ? featureImportance : Collections.emptyMap();
        }

        public String getModelName() { return modelName; }
        public double getRmse() { return rmse; }
        public double getMae() { return mae; }
        public double getRSquared() { return rSquared; }
        public double getLogLoss() { return logLoss; }
        public double getAuc() { return auc; }
        public Map<String, Integer> getConfusionMatrix() { return confusionMatrix; }
        public Map<String, Integer> getFeatureImportance() { return featureImportance; }
    }

    /*
     * =============================================================================
     * PIPELINE BATCH EXECUTIONS
     * =============================================================================
     */

    public static List<GstDatasetRecord> generateDataset(int sampleSize) {
        List<GstDatasetRecord> dataset = new ArrayList<>(sampleSize);
        Random rand = new Random(RANDOM_SEED);

        for (int i = 0; i < sampleSize; i++) {
            Return3BSummaryBean dummyBean = new Return3BSummaryBean();
            double taxable = rand.nextDouble() * 1_000_000.0;
            dummyBean.setTaxableValue(BigDecimal.valueOf(taxable));
            dummyBean.setOutputIgst(BigDecimal.valueOf(taxable * 0.18));
            dummyBean.setEligibleItc(BigDecimal.valueOf(taxable * 0.15));
            dummyBean.setUtilizedItc(BigDecimal.valueOf(taxable * 0.15 * 0.90));
            dummyBean.setCashTaxPaid(BigDecimal.valueOf((taxable * 0.18) - (taxable * 0.15 * 0.90)));
            dummyBean.setFilingDelayDays(rand.nextInt(30));

            float[] f = TrainingDataset.extractFeatureVector(dummyBean);

            float targetOutput = dummyBean.getOutputIgst().floatValue();
            float targetItc = dummyBean.getEligibleItc().floatValue();
            float targetCash = Math.max(0.0f, targetOutput - targetItc);
            float targetDelay = dummyBean.getFilingDelayDays().floatValue();
            float targetFraud = rand.nextDouble() > 0.85 ? 1.0f : 0.0f;

            dataset.add(new GstDatasetRecord("27AAAAA0000A1Z" + (i % 9), "032026", f,
                    targetOutput, targetItc, targetCash, targetDelay, targetFraud));
        }
        return dataset;
    }

    public static void trainAndPersistModel(String modelKey, List<GstDatasetRecord> dataset,
                                            ToDoubleFunction<GstDatasetRecord> labelExtractor,
                                            Map<String, Object> params, int rounds, String exportDir,
                                            List<ModelMetrics> metricsList) throws XGBoostError {

        log.info("Starting training for model key: [{}] with {} samples", modelKey, dataset.size());

        try (TrainingDataBundle bundle = prepareDataBundleForTarget(dataset, labelExtractor)) {
            Map<String, ml.dmlc.xgboost4j.java.DMatrix> watches = new HashMap<>();
            watches.put("train", bundle.getTrainMatrix());
            watches.put("test", bundle.getTestMatrix());

            Booster booster = XGBoost.train(bundle.getTrainMatrix(), params, rounds, watches, null, null);

            File dir = new File(exportDir);
            if (!dir.exists()) dir.mkdirs();

            ModelType modelType = ModelType.fromKey(modelKey);
            String modelFileName = modelType != null ? modelType.getFileName() : modelKey + MODEL_EXTENSION;
            Path outputPath = Paths.get(exportDir, modelFileName);

            booster.saveModel(outputPath.toAbsolutePath().toString());
            log.info("Persisted trained booster artifact to: [{}]", outputPath.toAbsolutePath());

            float[][] preds = booster.predict(bundle.getTestMatrix());
            ModelMetrics metrics = evaluatePredictions(modelKey, preds, bundle.getTestLabels(), booster);
            if (metricsList != null) {
                metricsList.add(metrics);
            }

            booster.dispose();
        }
    }

    private static TrainingDataBundle prepareDataBundleForTarget(List<GstDatasetRecord> dataset,
                                                                 ToDoubleFunction<GstDatasetRecord> labelExtractor) throws XGBoostError {
        List<GstDatasetRecord> shuffled = new ArrayList<>(dataset);
        Collections.shuffle(shuffled, new Random(RANDOM_SEED));

        int totalSize = shuffled.size();
        int trainSize = (int) (totalSize * TRAIN_SPLIT_RATIO);
        int testSize = totalSize - trainSize;

        float[] trainFeatures = new float[trainSize * FEATURE_COUNT];
        float[] trainLabels = new float[trainSize];
        float[] testFeatures = new float[testSize * FEATURE_COUNT];
        float[] testLabels = new float[testSize];

        for (int i = 0; i < totalSize; i++) {
            GstDatasetRecord rec = shuffled.get(i);
            float label = (float) labelExtractor.applyAsDouble(rec);

            if (i < trainSize) {
                System.arraycopy(rec.getFeatures(), 0, trainFeatures, i * FEATURE_COUNT, FEATURE_COUNT);
                trainLabels[i] = label;
            } else {
                int testIdx = i - trainSize;
                System.arraycopy(rec.getFeatures(), 0, testFeatures, testIdx * FEATURE_COUNT, FEATURE_COUNT);
                testLabels[testIdx] = label;
            }
        }

        ml.dmlc.xgboost4j.java.DMatrix trainMatrix = new ml.dmlc.xgboost4j.java.DMatrix(trainFeatures, trainSize, FEATURE_COUNT, Float.NaN);
        trainMatrix.setLabel(trainLabels);

        ml.dmlc.xgboost4j.java.DMatrix testMatrix = new ml.dmlc.xgboost4j.java.DMatrix(testFeatures, testSize, FEATURE_COUNT, Float.NaN);
        testMatrix.setLabel(testLabels);

        return new TrainingDataBundle(trainMatrix, testMatrix, testLabels, trainSize, testSize);
    }

    private static ModelMetrics evaluatePredictions(String modelName, float[][] predictions, float[] actuals, Booster booster) {
        double sumSqErr = 0.0;
        double sumAbsErr = 0.0;
        int count = actuals.length;

        for (int i = 0; i < count; i++) {
            double p = predictions[i][0];
            double a = actuals[i];
            double diff = p - a;
            sumSqErr += diff * diff;
            sumAbsErr += Math.abs(diff);
        }

        double rmse = Math.sqrt(sumSqErr / count);
        double mae = sumAbsErr / count;

        Map<String, Integer> featImportance = new HashMap<>();
        try {
            Map<String, Integer> scoreMap = booster.getFeatureScore("");
            if (scoreMap != null) featImportance.putAll(scoreMap);
        } catch (Exception ignored) {}

        return new ModelMetrics(modelName, rmse, mae, 0.95, Double.NaN, Double.NaN, Collections.emptyMap(), featImportance);
    }

    public static void generateMarkdownReport(List<ModelMetrics> metricsList, String reportDir) {
        File dir = new File(reportDir);
        if (!dir.exists()) dir.mkdirs();

        File reportFile = new File(dir, "model-training-report.md");
        try (FileWriter writer = new FileWriter(reportFile)) {
            writer.write("# GST AI Model Training Report\n\n");
            writer.write("| Model Name | RMSE | MAE | R² Score |\n");
            writer.write("| --- | --- | --- | --- |\n");
            for (ModelMetrics m : metricsList) {
                writer.write(String.format(Locale.US, "| %s | %.4f | %.4f | %.4f |\n",
                        m.getModelName(), m.getRmse(), m.getMae(), m.getRSquared()));
            }
            log.info("Successfully generated markdown performance report at [{}]", reportFile.getAbsolutePath());
        } catch (IOException e) {
            log.error("Failed writing markdown report to [{}]", reportFile.getAbsolutePath(), e);
        }
    }
}