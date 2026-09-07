package gov.com.ai.webapp.manager;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ml.dmlc.xgboost4j.java.Booster;
import ml.dmlc.xgboost4j.java.DMatrix;
import ml.dmlc.xgboost4j.java.XGBoost;
import ml.dmlc.xgboost4j.java.XGBoostError;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.File;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Component
@Slf4j
public class XGBoostModelManager {

    @Value("${ai.model.directory:./models}")
    private String modelExportDir;

    private final AtomicReference<Map<String, Booster>> activeRegistry = new AtomicReference<>(Collections.emptyMap());
    private final Map<String, ModelMetadata> metadataRegistry = new ConcurrentHashMap<>();
    private final AtomicBoolean isReady = new AtomicBoolean(false);

    // Aligned with TrainingDataset (71 dimensions)
    public static final int EXPECTED_FEATURE_COUNT = 71;

    public static final List<String> REQUIRED_MODELS = List.of(
            GstAiModelTrainer.MODEL_NAME_OUTPUT_TAX,
            GstAiModelTrainer.MODEL_NAME_ITC,
            GstAiModelTrainer.MODEL_NAME_CASH_TAX,
            GstAiModelTrainer.MODEL_NAME_DELAY,
            GstAiModelTrainer.MODEL_NAME_RISK
    );

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ModelMetadata implements Serializable {
        private static final long serialVersionUID = 1L;
        private String modelName;
        private String filePath;
        private long fileSizeBytes;
        private Instant lastModifiedTime;
        private Instant loadedTime;
        private boolean active;
    }

    @PostConstruct
    public synchronized void initialize() {
        log.info("[AI Engine] Initializing XGBoost Model Manager. Model Directory: [{}]", modelExportDir);
        reloadModels();
    }

    public synchronized boolean reloadModels() {
        log.info("[AI Engine] Triggering model reload sequence...");
        Map<String, Booster> newRegistry = new HashMap<>();
        Map<String, ModelMetadata> newMetadataMap = new HashMap<>();

        Path exportDirPath = Paths.get(modelExportDir);
        if (!Files.exists(exportDirPath)) {
            try {
                Files.createDirectories(exportDirPath);
            } catch (Exception ex) {
                log.error("[AI Engine] Failed to create model export directory", ex);
                return false;
            }
        }

        int loadedCount = 0;
        for (String modelName : REQUIRED_MODELS) {
            File modelFile = new File(modelExportDir, modelName + ".model");
            if (modelFile.exists() && modelFile.isFile() && modelFile.length() > 0) {
                try {
                    Booster booster = XGBoost.loadModel(modelFile.getAbsolutePath());
                    newRegistry.put(modelName, booster);

                    BasicFileAttributes attr = Files.readAttributes(modelFile.toPath(), BasicFileAttributes.class);
                    ModelMetadata meta = ModelMetadata.builder()
                            .modelName(modelName)
                            .filePath(modelFile.getAbsolutePath())
                            .fileSizeBytes(modelFile.length())
                            .lastModifiedTime(attr.lastModifiedTime().toInstant())
                            .loadedTime(Instant.now())
                            .active(true)
                            .build();

                    newMetadataMap.put(modelName, meta);
                    loadedCount++;
                } catch (Exception ex) {
                    log.error("[AI Engine] Error loading native booster: {}", modelFile.getName(), ex);
                }
            } else {
                log.warn("[AI Engine] Model file missing or empty: [{}]", modelFile.getAbsolutePath());
            }
        }

        Map<String, Booster> oldRegistry = activeRegistry.getAndSet(Collections.unmodifiableMap(newRegistry));
        metadataRegistry.clear();
        metadataRegistry.putAll(newMetadataMap);

        boolean allLoaded = (loadedCount == REQUIRED_MODELS.size());
        isReady.set(allLoaded);

        disposeRegistry(oldRegistry);
        log.info("[AI Engine] Model reload complete. Operational Status: {}. Loaded: {}/{}", allLoaded, loadedCount, REQUIRED_MODELS.size());

        return allLoaded;
    }

    /**
     * Safe execution of C++ native inference.
     */
    public Float runInference(String modelName, float[] fullFeatures) {
        Optional<Booster> optionalBooster = getBooster(modelName);
        if (optionalBooster.isEmpty()) {
            log.warn("[AI Engine] Requested model [{}] not ready or non-existent.", modelName);
            return null;
        }

        Booster booster = optionalBooster.get();
        float[] adaptedFeatures = adaptFeatureVector(fullFeatures, EXPECTED_FEATURE_COUNT);

        DMatrix dMatrix = null;
        try {
            dMatrix = new DMatrix(adaptedFeatures, 1, EXPECTED_FEATURE_COUNT, Float.NaN);
            float[][] results = booster.predict(dMatrix);
            if (results != null && results.length > 0 && results[0].length > 0) {
                return results[0][0];
            }
        } catch (XGBoostError e) {
            log.error("[AI Engine] Inference failure on model [{}]: {}", modelName, e.getMessage());
        } finally {
            if (dMatrix != null) {
                try {
                    dMatrix.dispose(); // Off-heap memory deallocation
                } catch (Exception e) {
                    log.warn("[AI Engine] Failure disposing DMatrix pointer", e);
                }
            }
        }
        return null;
    }

    public Optional<Booster> getBooster(String modelName) {
        return Optional.ofNullable(activeRegistry.get().get(modelName));
    }

    public Map<String, ModelMetadata> getMetadataRegistry() {
        return Collections.unmodifiableMap(metadataRegistry);
    }

    public boolean isReady() {
        return isReady.get();
    }

    public boolean isFullyOperational() {
        return isReady.get();
    }

    @PreDestroy
    public synchronized void shutdown() {
        log.info("[AI Engine] Shutting down XGBoost native memory allocations...");
        Map<String, Booster> currentRegistry = activeRegistry.getAndSet(Collections.emptyMap());
        disposeRegistry(currentRegistry);
        metadataRegistry.clear();
        isReady.set(false);
    }

    /**
     * Explicitly disposes of off-heap C++ Booster objects to prevent memory leaks.
     */
    private void disposeRegistry(Map<String, Booster> registry) {
        if (registry != null && !registry.isEmpty()) {
            registry.forEach((name, booster) -> {
                if (booster != null) {
                    try {
                        booster.dispose();
                        log.debug("[AI Engine] Freeing native off-heap memory for model: {}", name);
                    } catch (Exception ex) {
                        log.error("[AI Engine] Error freeing native memory for " + name, ex);
                    }
                }
            });
        }
    }

    private float[] adaptFeatureVector(float[] inputFeatures, int targetSize) {
        if (inputFeatures == null) {
            return new float[targetSize];
        }
        if (inputFeatures.length == targetSize) {
            return inputFeatures;
        }
        float[] adapted = new float[targetSize];
        System.arraycopy(inputFeatures, 0, adapted, 0, Math.min(inputFeatures.length, targetSize));
        return adapted;
    }
}