package ml.docilealligator.infinityforreddit.activities;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.text.SpannableString;
import android.text.util.Linkify;
import android.util.Log;
import android.view.InflateException;
import android.view.MenuItem;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.core.graphics.Insets;
import androidx.core.view.OnApplyWindowInsetsListener;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.greenrobot.eventbus.EventBus;

import java.util.List;
import java.util.concurrent.Executor;

import javax.inject.Inject;
import javax.inject.Named;

import me.saket.bettermovementmethod.BetterLinkMovementMethod;
import ml.docilealligator.infinityforreddit.Infinity;
import ml.docilealligator.infinityforreddit.R;
import ml.docilealligator.infinityforreddit.RedditDataRoomDatabase;
import ml.docilealligator.infinityforreddit.account.FetchMyInfo;
import ml.docilealligator.infinityforreddit.asynctasks.ParseAndInsertNewAccount;
import ml.docilealligator.infinityforreddit.customtheme.CustomThemeWrapper;
import ml.docilealligator.infinityforreddit.databinding.ActivityLoginBinding;
import ml.docilealligator.infinityforreddit.events.NewUserLoggedInEvent;
import ml.docilealligator.infinityforreddit.network.AccountCookieJar;
import ml.docilealligator.infinityforreddit.utils.APIUtils;
import ml.docilealligator.infinityforreddit.utils.SharedPreferencesUtils;
import ml.docilealligator.infinityforreddit.utils.Utils;
import okhttp3.Cookie;
import okhttp3.OkHttpClient;
import retrofit2.Retrofit;

public class LoginActivity extends BaseActivity {

    private static final String IS_AGREE_TO_USER_AGGREMENT_STATE = "IATUAS";

    @Inject
    @Named("no_oauth")
    Retrofit mRetrofit;
    @Inject
    @Named("oauth")
    Retrofit mOauthRetrofit;
    @Inject
    RedditDataRoomDatabase mRedditDataRoomDatabase;
    @Inject
    @Named("default")
    SharedPreferences mSharedPreferences;
    @Inject
    @Named("current_account")
    SharedPreferences mCurrentAccountSharedPreferences;
    @Inject
    CustomThemeWrapper mCustomThemeWrapper;
    @Inject
    Executor mExecutor;
    private boolean isAgreeToUserAgreement = true;
    private boolean loginHandled = false;
    private ActivityLoginBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ((Infinity) getApplication()).getAppComponent().inject(this);

        setImmersiveModeNotApplicableBelowAndroid16();

        super.onCreate(savedInstanceState);
        binding = ActivityLoginBinding.inflate(getLayoutInflater());

        try {
            setContentView(binding.getRoot());
        } catch (InflateException ie) {
            Log.e("LoginActivity", "Failed to inflate LoginActivity: " + ie.getMessage());
            Toast.makeText(LoginActivity.this, R.string.no_system_webview_error, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        applyCustomTheme();

        if (isImmersiveInterfaceRespectForcedEdgeToEdge()) {
            if (isChangeStatusBarIconColor()) {
                addOnOffsetChangedListener(binding.appbarLayoutLoginActivity);
            }

            ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), new OnApplyWindowInsetsListener() {
                @NonNull
                @Override
                public WindowInsetsCompat onApplyWindowInsets(@NonNull View v, @NonNull WindowInsetsCompat insets) {
                    Insets allInsets = Utils.getInsets(insets, true, isForcedImmersiveInterface());

                    setMargins(binding.toolbarLoginActivity,
                            allInsets.left,
                            allInsets.top,
                            allInsets.right,
                            BaseActivity.IGNORE_MARGIN);

                    binding.linearLayoutLoginActivity.setPadding(
                            allInsets.left,
                            0,
                            allInsets.right,
                            allInsets.bottom
                    );

                    setMargins(binding.fabLoginActivity,
                            BaseActivity.IGNORE_MARGIN,
                            BaseActivity.IGNORE_MARGIN,
                            (int) Utils.convertDpToPixel(16, LoginActivity.this) + allInsets.right,
                            (int) Utils.convertDpToPixel(16, LoginActivity.this) + allInsets.bottom);

                    return WindowInsetsCompat.CONSUMED;
                }
            });
        }

        setSupportActionBar(binding.toolbarLoginActivity);

        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        if (savedInstanceState != null) {
            isAgreeToUserAgreement = savedInstanceState.getBoolean(IS_AGREE_TO_USER_AGGREMENT_STATE);
        }

        binding.webviewLoginActivity.getSettings().setJavaScriptEnabled(true);

        String userAgent = binding.webviewLoginActivity.getSettings().getUserAgentString();
        String chromeUserAgent = userAgent
                .replace("; wv)", ")")
                .replace("Version/4.0 ", "");
        binding.webviewLoginActivity.getSettings().setUserAgentString(chromeUserAgent);

        // Log in through Reddit's real web login page (not the OAuth authorize page) so we can
        // harvest the resulting browser session cookies instead of an OAuth code.
        String url = "https://www.reddit.com/login";

        binding.internetDisconnectedErrorRetryButtonLoginActivity.setOnClickListener(view -> {
            recreate();
        });

        // No more Chrome-Custom-Tab login alternative: a Custom Tab has no way to hand HttpOnly
        // session cookies back to this app, only a WebView's CookieManager can, so it's retired.
        binding.fabLoginActivity.setVisibility(View.GONE);

        CookieManager.getInstance().removeAllCookies(aBoolean -> {
        });

        binding.webviewLoginActivity.loadUrl(url);
        binding.webviewLoginActivity.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                view.loadUrl(url);
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (loginHandled) {
                    return;
                }
                // A successful login leaves the /login page for reddit's home feed; anything still
                // under /login (or the account creation flow) means the user isn't done yet.
                if (url.contains("reddit.com/login") || url.contains("reddit.com/register")) {
                    return;
                }

                String cookieHeader = CookieManager.getInstance().getCookie(APIUtils.API_BASE_URI);
                if (cookieHeader == null || !cookieHeader.contains("reddit_session")) {
                    return;
                }

                loginHandled = true;
                harvestSessionAndFetchIdentity(cookieHeader);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame() && !Utils.isConnectedToInternet(LoginActivity.this)) {
                    binding.internetDisconnectedErrorLinearLayoutLoginActivity.setVisibility(View.VISIBLE);
                } else {
                    super.onReceivedError(view, request, error);
                }
            }
        });

        if (!isAgreeToUserAgreement) {
            TextView messageTextView = new TextView(this);
            int padding = (int) Utils.convertDpToPixel(24, this);
            messageTextView.setPaddingRelative(padding, padding, padding, padding);
            SpannableString message = new SpannableString(getString(R.string.user_agreement_message, "https://www.redditinc.com/policies/user-agreement", "https://docile-alligator.github.io"));
            Linkify.addLinks(message, Linkify.WEB_URLS);
            messageTextView.setMovementMethod(BetterLinkMovementMethod.newInstance().setOnLinkClickListener(new BetterLinkMovementMethod.OnLinkClickListener() {
                @Override
                public boolean onClick(TextView textView, String url) {
                    Intent intent = new Intent(LoginActivity.this, LinkResolverActivity.class);
                    intent.setData(Uri.parse(url));
                    startActivity(intent);
                    return true;
                }
            }));
            messageTextView.setLinkTextColor(getResources().getColor(R.color.colorAccent));
            messageTextView.setText(message);
            new MaterialAlertDialogBuilder(this, R.style.MaterialAlertDialogTheme)
                    .setTitle(getString(R.string.user_agreement_dialog_title))
                    .setView(messageTextView)
                    .setPositiveButton(R.string.agree, (dialogInterface, i) -> isAgreeToUserAgreement = true)
                    .setNegativeButton(R.string.do_not_agree, (dialogInterface, i) -> finish())
                    .setCancelable(false)
                    .show();
        }

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (binding.webviewLoginActivity.canGoBack()) {
                    binding.webviewLoginActivity.goBack();
                } else {
                    setEnabled(false);
                    triggerBackPress();
                }
            }
        });
    }

    /**
     * Replays the freshly-harvested browser session cookies through a one-off OkHttp client (the
     * account doesn't exist in Room yet, so the persistent {@link AccountCookieJar} has nothing to
     * load) to fetch identity info and the session's modhash, then persists the new account.
     */
    private void harvestSessionAndFetchIdentity(String cookieHeader) {
        List<Cookie> cookies = AccountCookieJar.parseCookieHeader(cookieHeader, APIUtils.API_BASE_URI);
        String sessionCookiesJson = AccountCookieJar.serialize(cookies);

        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(chain -> chain.proceed(chain.request().newBuilder()
                        .header("Cookie", cookieHeader)
                        .build()))
                .build();
        Retrofit identityRetrofit = mRetrofit.newBuilder().client(client).build();

        FetchMyInfo.fetchAccountInfo(mExecutor, mHandler, identityRetrofit, mRedditDataRoomDatabase,
                new FetchMyInfo.FetchMyInfoListener() {
                    @Override
                    public void onFetchMyInfoSuccess(String name, String profileImageUrl, String bannerImageUrl, int karma, boolean isMod, String modhash) {
                        mCurrentAccountSharedPreferences.edit().putString(SharedPreferencesUtils.ACCESS_TOKEN, modhash)
                                .putString(SharedPreferencesUtils.ACCOUNT_NAME, name)
                                .putString(SharedPreferencesUtils.ACCOUNT_IMAGE_URL, profileImageUrl).apply();
                        mCurrentAccountSharedPreferences.edit().remove(SharedPreferencesUtils.SUBSCRIBED_THINGS_SYNC_TIME).apply();
                        ParseAndInsertNewAccount.parseAndInsertNewAccount(mExecutor, new Handler(), name, modhash,
                                sessionCookiesJson, profileImageUrl, bannerImageUrl, karma, isMod,
                                mRedditDataRoomDatabase.accountDao(),
                                () -> {
                                    EventBus.getDefault().post(new NewUserLoggedInEvent());
                                    finish();
                                });
                    }

                    @Override
                    public void onFetchMyInfoFailed(boolean parseFailed) {
                        loginHandled = false;
                        if (parseFailed) {
                            Toast.makeText(LoginActivity.this, R.string.parse_user_info_error, Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(LoginActivity.this, R.string.cannot_fetch_user_info, Toast.LENGTH_SHORT).show();
                        }
                    }
                });
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(IS_AGREE_TO_USER_AGGREMENT_STATE, isAgreeToUserAgreement);
    }

    @Override
    public SharedPreferences getDefaultSharedPreferences() {
        return mSharedPreferences;
    }

    @Override
    public SharedPreferences getCurrentAccountSharedPreferences() {
        return mCurrentAccountSharedPreferences;
    }

    @Override
    public CustomThemeWrapper getCustomThemeWrapper() {
        return mCustomThemeWrapper;
    }

    @Override
    protected void applyCustomTheme() {
        int backgroundColor = mCustomThemeWrapper.getBackgroundColor();
        binding.getRoot().setBackgroundColor(backgroundColor);
        applyAppBarLayoutAndCollapsingToolbarLayoutAndToolbarTheme(binding.appbarLayoutLoginActivity, null, binding.toolbarLoginActivity);
        int primaryTextColor = mCustomThemeWrapper.getPrimaryTextColor();
        binding.twoFaInfOTextViewLoginActivity.setTextColor(primaryTextColor);
        Drawable infoDrawable = Utils.getTintedDrawable(this, R.drawable.ic_info_preference_day_night_24dp, mCustomThemeWrapper.getPrimaryIconColor());
        binding.twoFaInfOTextViewLoginActivity.setCompoundDrawablesWithIntrinsicBounds(infoDrawable, null, null, null);
        binding.internetDisconnectedErrorLinearLayoutLoginActivity.setBackgroundColor(backgroundColor);
        binding.internetDisconnectedErrorTextViewLoginActivity.setTextColor(primaryTextColor);
        binding.internetDisconnectedErrorRetryButtonLoginActivity.setTextColor(mCustomThemeWrapper.getButtonTextColor());
        binding.internetDisconnectedErrorRetryButtonLoginActivity.setBackgroundColor(mCustomThemeWrapper.getColorPrimaryLightTheme());
        applyFABTheme(binding.fabLoginActivity);
        if (typeface != null) {
            binding.twoFaInfOTextViewLoginActivity.setTypeface(typeface);
            binding.internetDisconnectedErrorTextViewLoginActivity.setTypeface(typeface);
            binding.internetDisconnectedErrorRetryButtonLoginActivity.setTypeface(typeface);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return false;
    }
}
