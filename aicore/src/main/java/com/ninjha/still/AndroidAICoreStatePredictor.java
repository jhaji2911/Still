package com.ninjha.still;

import com.google.mlkit.genai.common.DownloadCallback;
import com.google.mlkit.genai.common.FeatureStatus;
import com.google.mlkit.genai.common.GenAiException;
import com.google.mlkit.genai.prompt.GenerateContentRequest;
import com.google.mlkit.genai.prompt.GenerateContentResponse;
import com.google.mlkit.genai.prompt.Generation;
import com.google.mlkit.genai.prompt.TextPart;
import com.google.mlkit.genai.prompt.java.GenerativeModelFutures;
import com.ninjha.still.core.ContextToken;
import com.ninjha.still.core.DevicePlace;
import com.ninjha.still.core.EnviroDecibel;
import com.ninjha.still.core.MotionState;
import com.ninjha.still.core.Peripheral;
import com.ninjha.still.core.StillState;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class AndroidAICoreStatePredictor {
    private static final Set<String> VALID_LABELS = new HashSet<>(
        Arrays.asList("Training", "Meditating", "Commuting", "Deep work", "Available", "Away")
    );

    private final GenerativeModelFutures generativeModel =
        GenerativeModelFutures.from(Generation.INSTANCE.getClient());

    public StillState predict(ContextToken token) throws Exception {
        ensureModelReady();

        GenerateContentRequest.Builder builder =
            new GenerateContentRequest.Builder(new TextPart(promptFor(token)));
        builder.setTemperature(0.0f);
        builder.setTopK(1);
        builder.setCandidateCount(1);
        builder.setMaxOutputTokens(180);

        GenerateContentResponse response = generativeModel.generateContent(builder.build()).get();
        String text = "";
        if (!response.getCandidates().isEmpty() && response.getCandidates().get(0).getText() != null) {
            text = response.getCandidates().get(0).getText();
        }
        return parseState(text);
    }

    private void ensureModelReady() throws Exception {
        int status = generativeModel.checkStatus().get();
        if (status == FeatureStatus.AVAILABLE) {
            return;
        }
        if (status == FeatureStatus.DOWNLOADABLE) {
            downloadModel();
            if (generativeModel.checkStatus().get() == FeatureStatus.AVAILABLE) {
                return;
            }
        }
        if (status == FeatureStatus.DOWNLOADING) {
            throw new IllegalStateException("Android AICore model is still downloading");
        }
        throw new IllegalStateException("Android AICore Gemini Nano is unavailable on this device");
    }

    private void downloadModel() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        DownloadFailure failure = new DownloadFailure();

        generativeModel.download(new DownloadCallback() {
            @Override
            public void onDownloadStarted(long bytesToDownload) {
            }

            @Override
            public void onDownloadProgress(long totalBytesDownloaded) {
            }

            @Override
            public void onDownloadCompleted() {
                latch.countDown();
            }

            @Override
            public void onDownloadFailed(GenAiException exception) {
                failure.exception = exception;
                latch.countDown();
            }
        }).get();

        if (!latch.await(5, TimeUnit.MINUTES)) {
            throw new IllegalStateException("Android AICore model download timed out");
        }
        if (failure.exception != null) {
            throw failure.exception;
        }
    }

    private String promptFor(ContextToken token) {
        return "Predict Nishant's availability state from coarse on-device sensor tokens.\n"
            + "Return only compact JSON with this exact schema:\n"
            + "{\"label\":\"Training|Meditating|Commuting|Deep work|Available|Away\","
            + "\"confidence\":0.0,\"estimatedReturnMinutes\":0,"
            + "\"autoReply\":\"one sentence in Nishant's voice\","
            + "\"reasons\":[\"short reason\",\"short reason\"]}\n\n"
            + "Rules:\n"
            + "- Use only the token values below.\n"
            + "- Do not invent private details.\n"
            + "- Confidence must be between 0.20 and 0.98.\n"
            + "- estimatedReturnMinutes must be one of 5, 10, 20, 30, 45.\n\n"
            + "- If deviceLocked=true and isCharging=true, prefer Away.\n"
            + "- If devicePlace=in_hand and deviceLocked=false, prefer Available.\n\n"
            + "Tokens:\n"
            + "motion=" + motionValue(token.getMotion()) + "\n"
            + "physioStress=" + token.getPhysioStress().name() + "\n"
            + "ambientAudio=" + audioValue(token.getAmbientAudio()) + "\n"
            + "devicePlace=" + placeValue(token.getDevicePlace()) + "\n"
            + "peripheral=" + peripheralValue(token.getPeripheral()) + "\n"
            + "heartRateBpm=" + (token.getHeartRateBpm() == null ? "unknown" : token.getHeartRateBpm()) + "\n"
            + "deviceLocked=" + token.isDeviceLocked() + "\n"
            + "isCharging=" + token.isCharging() + "\n"
            + "sessionSteps=" + token.getSessionSteps();
    }

    private StillState parseState(String text) throws JSONException {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new JSONException("Android AICore returned non-JSON state");
        }

        JSONObject json = new JSONObject(text.substring(start, end + 1));
        String label = json.optString("label", "Available");
        if (!VALID_LABELS.contains(label)) {
            label = "Available";
        }

        List<String> reasons = new ArrayList<>();
        JSONArray reasonArray = json.optJSONArray("reasons");
        if (reasonArray != null) {
            for (int index = 0; index < reasonArray.length(); index++) {
                String reason = reasonArray.optString(index, "");
                if (!reason.isEmpty()) {
                    reasons.add(reason);
                }
            }
        }
        if (reasons.isEmpty()) {
            reasons.add("Android AICore prediction");
        }

        return new StillState(
            label,
            (float) Math.max(0.2, Math.min(0.98, json.optDouble("confidence", 0.5))),
            Math.max(1, json.optInt("estimatedReturnMinutes", 5)),
            emptyToDefault(json.optString("autoReply", ""), "Nishant will reply soon."),
            reasons
        );
    }

    private String emptyToDefault(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    private String motionValue(MotionState motion) {
        switch (motion) {
            case Still:
                return "still";
            case MicroVibration:
                return "micro_vibration";
            case HighMotion:
                return "high_motion";
            default:
                return motion.name();
        }
    }

    private String audioValue(EnviroDecibel audio) {
        switch (audio) {
            case Silent:
                return "silent";
            case Rhythmic:
                return "rhythmic";
            case Chaotic:
                return "chaotic";
            default:
                return audio.name();
        }
    }

    private String placeValue(DevicePlace place) {
        switch (place) {
            case Pocket:
                return "pocket";
            case FaceDown:
                return "face_down";
            case ChargingStand:
                return "charging_stand";
            case InHand:
                return "in_hand";
            default:
                return place.name();
        }
    }

    private String peripheralValue(Peripheral peripheral) {
        switch (peripheral) {
            case Headphones:
                return "headphones";
            case CarBluetooth:
                return "car_bluetooth";
            case None:
                return "none";
            default:
                return peripheral.name();
        }
    }

    private static final class DownloadFailure {
        private GenAiException exception;
    }
}
