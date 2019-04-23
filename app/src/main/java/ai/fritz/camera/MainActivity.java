package ai.fritz.camera;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Canvas;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.util.Size;
import android.widget.TextView;
import android.Manifest;
import android.content.pm.PackageManager;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import android.net.Uri;


import java.io.InputStream;
import java.util.ArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import ai.fritz.core.Fritz;
import ai.fritz.core.FritzOnDeviceModel;

import ai.fritz.fritzvisionobjectmodel.ObjectDetectionOnDeviceModel;
import ai.fritz.vision.FritzVision;
import ai.fritz.vision.FritzVisionImage;
import ai.fritz.vision.FritzVisionOrientation;
import ai.fritz.vision.objectdetection.FritzVisionObjectPredictor;
import ai.fritz.vision.objectdetection.FritzVisionObjectPredictorOptions;
import ai.fritz.vision.objectdetection.FritzVisionObjectResult;

import com.ibm.watson.developer_cloud.android.library.audio.MicrophoneHelper;
import com.ibm.watson.developer_cloud.android.library.audio.MicrophoneInputStream;
import com.ibm.watson.developer_cloud.android.library.audio.utils.ContentType;
import com.ibm.watson.developer_cloud.speech_to_text.v1.SpeechToText;
import com.ibm.watson.developer_cloud.speech_to_text.v1.model.RecognizeOptions;
import com.ibm.watson.developer_cloud.speech_to_text.v1.model.SpeechRecognitionResults;
import com.ibm.watson.developer_cloud.speech_to_text.v1.websocket.BaseRecognizeCallback;



public class MainActivity extends BaseCameraActivity implements ImageReader.OnImageAvailableListener {

    private static final String TAG = MainActivity.class.getSimpleName();

    private static final Size DESIRED_PREVIEW_SIZE = new Size(1280, 960);

    private AtomicBoolean computing = new AtomicBoolean(false);

    private FritzVisionObjectPredictor objectPredictor;
    private FritzVisionObjectResult objectResult;
    private FritzVisionImage fritzVisionImage;
    private CustomTFLiteClassifier classifier;
    private int imageRotation;
    private Intent customModelIntent;


    private int object = -1;
    private AlertDialog alert;
    private TextView label;
    private Boolean dup = false;
    private Boolean empty = true;
    private ArrayList<String> listOfObjects = new ArrayList<String>();

    private TextView inputMessage;
    private boolean permissionToRecordAccepted = false;
    private static final int REQUEST_RECORD_AUDIO_PERMISSION = 200;
    private static final int RECORD_REQUEST_CODE = 101;
    private SpeechToText speechService;
    private MicrophoneInputStream capture;
    private MicrophoneHelper microphoneHelper;

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        inputMessage = findViewById(R.id.message);
        microphoneHelper = new MicrophoneHelper(this);

        int permission = ContextCompat.checkSelfPermission(this,
                Manifest.permission.RECORD_AUDIO);

        if (permission != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "Permission to record denied");
            makeRequest();
        }

        // Initialize Fritz
        Fritz.configure(this,"c8df3628771648f2960de5e3fca29053");
        label = (TextView)findViewById(R.id.textView);

        // STEP 1: Get the predictor and set the options.

        classifier = new CustomTFLiteClassifier(this);
        // ----------------------------------------------
        // END STEP 1

    }

    @Override
    protected int getLayoutId() {
        return R.layout.camera_connection_fragment_stylize;
    }

    @Override
    protected Size getDesiredPreviewFrameSize() {
        return DESIRED_PREVIEW_SIZE;
    }

    @Override
    public void onPreviewSizeChosen(final Size previewSize, final Size cameraViewSize, final int rotation) {

        imageRotation = FritzVisionOrientation.getImageRotationFromCamera(this, cameraId);

        FritzVisionObjectPredictorOptions options = new FritzVisionObjectPredictorOptions.Builder()
                .confidenceThreshold(.65f).build();

        FritzOnDeviceModel onDeviceModel = new ObjectDetectionOnDeviceModel();
        objectPredictor = FritzVision.ObjectDetection.getPredictor(onDeviceModel,options);

        // Callback draws a canvas on the OverlayView
        addCallback(
                new OverlayView.DrawCallback() {
                    @Override
                    public void drawCallback(final Canvas canvas) {
                        // STEP 4: Draw the prediction result
                            switch (object) {
                                case 0:
                                    label.setText("MacBook");
                                    break;
                                case 1:
                                    label.setText("Blender Bottle");
                                    break;
                                case 2:
                                    label.setText("Gloves");
                                    break;
                                case 3:
                                    label.setText("Lock");
                                    break;
                                case 4:
                                    label.setText("Remote Controller");
                                    break;
                                case 5:
                                    label.setText("Vigileo Monitor");
                                    break;
                            }

                            for(int x = 0; x < listOfObjects.size();x++){
                                if(listOfObjects.get(x).equals(label.getText())){
                                    dup = true;
                                }
                            }

                            if (!(label.getText().equals("")) && dup == false && empty == true) {
                                listOfObjects.add(label.getText()+"");
                                empty = false;

                            AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
                            builder.setMessage("Is " + label.getText().toString() + " the correct object? Please say outloud yes or no");

                            alert = builder.create();
                            alert.show();

                            listenToSpeech();

                        }

                        dup = false;
                            // alert.getButton(DialogInterface.BUTTON_POSITIVE).performClick();

                            // finish();
                            // customModelIntent = new Intent(MainActivity.this, ChosenCustomModel.class);
                            // MainActivity.this.startActivity(customModelIntent);

                        }
                });
    }

    @Override
    public void onImageAvailable(final ImageReader reader) {
        Image image = reader.acquireLatestImage();

        if (image == null) {
            return;
        }

        if (!computing.compareAndSet(false, true)) {
            image.close();
            return;
        }

        // STEP 2: Create the FritzVisionImage object from media.Image
        fritzVisionImage  = FritzVisionImage.fromMediaImage(image, imageRotation);

        // ------------------------------------------------------------------------
        // END STEP 2

        image.close();

        runInBackground(
                new Runnable() {
                    @Override
                    public void run() {
                        // STEP 3: Run predict on the image
                        objectResult = objectPredictor.predict(fritzVisionImage);

                        object = classifier.classify(fritzVisionImage.getBitmap());


                        // Fire callback to change the OverlayView
                        requestRender();
                        computing.set(false);
                    }
                });
    }

    // Speech to Text Record Audio permission
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        switch (requestCode) {
            case REQUEST_RECORD_AUDIO_PERMISSION:
                permissionToRecordAccepted = grantResults[0] == PackageManager.PERMISSION_GRANTED;
                break;
            case RECORD_REQUEST_CODE: {

                if (grantResults.length == 0
                        || grantResults[0] !=
                        PackageManager.PERMISSION_GRANTED) {

                    Log.i(TAG, "Permission has been denied by user");
                } else {
                    Log.i(TAG, "Permission has been granted by user");
                }
                return;
            }
            case MicrophoneHelper.REQUEST_PERMISSION: {
                if (grantResults.length > 0 && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Permission to record audio denied", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    protected void makeRequest() {
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.RECORD_AUDIO},
                MicrophoneHelper.REQUEST_PERMISSION);
    }

    //Record a message via Watson Speech to Text
    private void listenToSpeech() {
        speechService = new SpeechToText();
        //Use "apikey" as username and apikey as your password
        speechService.setUsernameAndPassword("apikey", "lRU9TpbvfY8Z5Jpcg4MXx3cpnYMcWNrlhlAyf5PZ4ott");
        //Default: https://stream.watsonplatform.net/text-to-speech/api
        speechService.setEndPoint("https://gateway-lon.watsonplatform.net/speech-to-text/api");

        capture = microphoneHelper.getInputStream(true);
        Log.d("tag","Listen to speech in MainActicvity");

        Toast.makeText(MainActivity.this,"Listening...", Toast.LENGTH_LONG).show();

        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    speechService.recognizeUsingWebSocket(getRecognizeOptions(capture), new MicrophoneRecognizeDelegate());
                } catch (Exception e) {
                    showError(e);
                }
            }
        }).start();

    }

    //Private Methods - Speech to Text
    private RecognizeOptions getRecognizeOptions(InputStream audio) {
        return new RecognizeOptions.Builder()
                .audio(audio)
                .contentType(ContentType.OPUS.toString())
                .model("en-US_BroadbandModel")
                .interimResults(true)
                .inactivityTimeout(2000)
                //TODO: Uncomment this to enable Speaker Diarization
                //.speakerLabels(true)
                .build();
    }

    private class MicrophoneRecognizeDelegate extends BaseRecognizeCallback {

        @Override
        public void onTranscription(SpeechRecognitionResults speechResults) {
            System.out.println(speechResults);

            if(speechResults.getResults() != null && !speechResults.getResults().isEmpty()) {
                String text = speechResults.getResults().get(0).getAlternatives().get(0).getTranscript();
                Log.d("tag", text);

                if (text.toLowerCase().contains("yes")){
                    microphoneHelper.closeInputStream();

                    int last = listOfObjects.size()-1;
                    switch (listOfObjects.get(last)) {
                        case "Blender Bottle":
                            startActivity(new Intent(MainActivity.this, BottleActivity.class));
                            break;
                        case "Gloves":
                            startActivity(new Intent(MainActivity.this, GlovesActivity.class));
                            break;
                        case "Remote Controller":
                            startActivity(new Intent(MainActivity.this, pdf.class));
                            break;
                        case "Vigileo Monitor":
                            startActivity(new Intent(MainActivity.this, MonitorActivity.class));
                            break;
                    }

                    finish();
                    listOfObjects.clear();
                    alert.dismiss();
                    empty = true;

                    //showToast("Listening Stopped...");

                }else if (text.toLowerCase().contains("no")){
                    microphoneHelper.closeInputStream();
                    alert.dismiss();
                    empty = true;
                    //showToast("Listening Stopped...");
                }else {
                    showToast("Sorry, I didn't catch it. Please say again!");
                }
            }
        }


        @Override public void onConnected() {

        }

        @Override
        public void onInactivityTimeout(RuntimeException runtimeException) {

        }

        @Override
        public void onListening() {

        }

        @Override
        public void onTranscriptionComplete() {

        }
    }

    private void showToast(final String message) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                Toast.makeText(MainActivity.this,message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void showError(final Exception e) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                Toast.makeText(MainActivity.this, e.getMessage(), Toast.LENGTH_SHORT).show();
                e.printStackTrace();
            }
        });
    }
}
