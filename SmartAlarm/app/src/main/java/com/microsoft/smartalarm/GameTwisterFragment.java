package com.microsoft.smartalarm;

import android.content.Context;
import android.content.res.Resources;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.microsoft.projectoxford.speechrecognition.Confidence;
import com.microsoft.projectoxford.speechrecognition.ISpeechRecognitionServerEvents;
import com.microsoft.projectoxford.speechrecognition.MicrophoneRecognitionClient;
import com.microsoft.projectoxford.speechrecognition.RecognitionResult;
import com.microsoft.projectoxford.speechrecognition.RecognitionStatus;
import com.microsoft.projectoxford.speechrecognition.RecognizedPhrase;
import com.microsoft.projectoxford.speechrecognition.SpeechRecognitionMode;
import com.microsoft.projectoxford.speechrecognition.SpeechRecognitionServiceFactory;

import java.util.Random;

public class GameTwisterFragment extends Fragment implements ISpeechRecognitionServerEvents {
    GameResultListener mCallback;

    public interface GameResultListener {
        void onGameSuccess();
        void onGameFailure();
    }

    private static String LOGTAG = "GameTwisterFragment";

    private MicrophoneRecognitionClient mMicClient = null;
    private SpeechRecognitionMode mRecognitionMode;
    private String mUnderstoodText = null;
    private String mQuestion = null;
    private ProgressButton mCaptureButton;
    private CountDownTimerView mTimer;
    private GameStateBanner mStateBanner;
    private TextView mTextResponse;

    private final static int TIMEOUT_MILLISECONDS = 30000;
    private final static float SUCCESS_THRESHOLD = 0.5f;

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.activity_twister_game, container, false);
        mTimer = (CountDownTimerView) view.findViewById(R.id.countdown_timer);
        mStateBanner = (GameStateBanner) view.findViewById(R.id.game_state);
        mTextResponse = (TextView) view.findViewById(R.id.understood_text);

        generateQuestion(view);
        initialize(view);

        Logger.init(getContext());
        Loggable.UserAction userAction = new Loggable.UserAction(Loggable.Key.ACTION_GAME_TWISTER);
        Logger.track(userAction);
        return view;
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        mCallback = (GameResultListener) context;
    }

    @Override
    public void onResume() {
        super.onResume();
        AlarmUtils.setLockScreenFlags(getActivity().getWindow());
        mTimer.start();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Logger.flush();
    }

    private void generateQuestion(View view) {
        Resources resources = getResources();
        String[] questions = resources.getStringArray(R.array.tongue_twisters);
        mQuestion = questions[new Random().nextInt(questions.length)];

        final TextView instructionTextView = (TextView) view.findViewById(R.id.instruction_text);
        instructionTextView.setText(mQuestion);
    }

    protected void gameSuccess() {
        mTimer.stop();
        String successMessage = getString(R.string.game_success_message);
        mStateBanner.success(successMessage, new GameStateBanner.Command() {
            @Override
            public void execute() {
                mCallback.onGameSuccess();
            }
        });
    }
    protected void gameFailure(boolean allowRetry) {
        if (allowRetry) {
            String failureMessage = getString(R.string.game_failure_message);
            mStateBanner.failure(failureMessage, new GameStateBanner.Command() {
                @Override
                public void execute() {
                    mCaptureButton.readyAudio();
                }
            });
        }
        else {
            Loggable.UserAction userAction = new Loggable.UserAction(Loggable.Key.ACTION_GAME_TWISTER_TIMEOUT);
            userAction.putProp(Loggable.Key.PROP_QUESTION, mQuestion);
            Logger.track(userAction);
            String failureMessage = getString(R.string.game_time_up_message);
            mStateBanner.failure(failureMessage, new GameStateBanner.Command() {
                @Override
                public void execute() {
                    mCallback.onGameFailure();
                }
            });
        }
    }

    @Override
    public void onPartialResponseReceived(String s) {
        Log.d(LOGTAG, s);
        mTextResponse.setText(s);
    }

    @Override
    public void onFinalResponseReceived(RecognitionResult response) {
        boolean isFinalDictationMessage = mRecognitionMode == SpeechRecognitionMode.LongDictation &&
                (response.RecognitionStatus == RecognitionStatus.EndOfDictation ||
                        response.RecognitionStatus == RecognitionStatus.DictationEndSilenceTimeout);
        if (mRecognitionMode == SpeechRecognitionMode.ShortPhrase
                || isFinalDictationMessage) {
            mMicClient.endMicAndRecognition();
            mCaptureButton.readyAudio();
            for (RecognizedPhrase res : response.Results) {
                Log.d(LOGTAG, String.valueOf(res.Confidence));
                Log.d(LOGTAG, String.valueOf(res.DisplayText));

                if(res.Confidence == Confidence.Normal) {
                    mUnderstoodText = res.DisplayText;
                }
                else if(res.Confidence == Confidence.High) {
                    mUnderstoodText = res.DisplayText;
                    break;
                }
            }

            mTextResponse.setText(mUnderstoodText);
            verify();
        }
    }

    @Override
    public void onIntentReceived(final String s) {
        Log.d(LOGTAG, s);
    }

    @Override
    public void onError(int errorCode, final String s) {
        Loggable.AppError error = new Loggable.AppError(Loggable.Key.APP_ERROR, s);
        Logger.track(error);
    }

    @Override
    public void onAudioEvent(boolean recording) {
        if (!recording) {
            mMicClient.endMicAndRecognition();
            mCaptureButton.readyAudio();
        }
    }

    private void initialize(View view) {
        mRecognitionMode = SpeechRecognitionMode.ShortPhrase;

        try {
            //TODO: localize
            String language = "en-us";
            String subscriptionKey = Util.getToken(getActivity(), "speech");
            if (mMicClient == null) {
                mMicClient = SpeechRecognitionServiceFactory.createMicrophoneClient(getActivity(), mRecognitionMode, language, this, subscriptionKey);
            }
        }
        catch(Exception e){
            Logger.trackException(e);
        }


        mCaptureButton = (ProgressButton) view.findViewById(R.id.capture_button);

        mCaptureButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View arg0) {
                if (mCaptureButton.isReady()) {
                    mMicClient.startMicAndRecognition();
                    mCaptureButton.waiting();
                } else {
                    mMicClient.endMicAndRecognition();
                    mCaptureButton.readyAudio();
                }
            }
        });
        mCaptureButton.readyAudio();

        mTimer = (CountDownTimerView) view.findViewById(R.id.countdown_timer);
        mTimer.init(TIMEOUT_MILLISECONDS, new CountDownTimerView.Command() {
            @Override
            public void execute() {
                gameFailure(false);
            }
        });
    }


    //https://en.wikibooks.org/wiki/Algorithm_Implementation/Strings/Levenshtein_distance
    public int levenshteinDistance(CharSequence lhs, CharSequence rhs) {
        if (lhs == null && rhs == null) {
            return 0;
        }
        if (lhs == null) {
            return rhs.length();
        }
        if (rhs == null) {
            return lhs.length();
        }

        int len0 = lhs.length() + 1;
        int len1 = rhs.length() + 1;

        // the array of distances
        int[] cost = new int[len0];
        int[] newcost = new int[len0];

        // initial cost of skipping prefix in String s0
        for (int i = 0; i < len0; i++) cost[i] = i;

        // dynamically computing the array of distances

        // transformation cost for each letter in s1
        for (int j = 1; j < len1; j++) {
            // initial cost of skipping prefix in String s1
            newcost[0] = j;

            // transformation cost for each letter in s0
            for(int i = 1; i < len0; i++) {
                // matching current letters in both strings
                int match = (lhs.charAt(i - 1) == rhs.charAt(j - 1)) ? 0 : 1;

                // computing cost for each transformation
                int cost_replace = cost[i - 1] + match;
                int cost_insert  = cost[i] + 1;
                int cost_delete  = newcost[i - 1] + 1;

                // keep minimum cost
                newcost[i] = Math.min(Math.min(cost_insert, cost_delete), cost_replace);
            }

            // swap cost/newcost arrays
            int[] swap = cost; cost = newcost; newcost = swap;
        }

        // the distance is the cost for transforming all letters in both strings
        return cost[len0 - 1];
    }

    private void verify() {
        if (mUnderstoodText == null) {
            gameFailure(true);
            return;
        }

        double distance = (double)levenshteinDistance(mUnderstoodText, mQuestion) / (double)mQuestion.length();

        Loggable.UserAction userAction = new Loggable.UserAction(Loggable.Key.ACTION_GAME_TWISTER_SUCCESS);
        userAction.putProp(Loggable.Key.PROP_QUESTION, mQuestion);
        userAction.putProp(Loggable.Key.PROP_DIFF, distance);

        if (distance <= SUCCESS_THRESHOLD) {
            Logger.track(userAction);
            gameSuccess();
        }
        else {
            userAction.Name = Loggable.Key.ACTION_GAME_TWISTER_FAIL;
            Logger.track(userAction);
            gameFailure(true);
        }
    }
}
