package com.zegoggles.smssync.activity.donation;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.SkuDetails;
import com.android.billingclient.api.SkuDetailsResponseListener;
import com.zegoggles.smssync.BuildConfig;
import com.zegoggles.smssync.R;
import com.zegoggles.smssync.activity.donation.DonationActivity.DonationStatusListener.State;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import static com.android.billingclient.api.BillingClient.BillingResponse.BILLING_UNAVAILABLE;
import static com.android.billingclient.api.BillingClient.BillingResponse.ITEM_ALREADY_OWNED;
import static com.android.billingclient.api.BillingClient.BillingResponse.ITEM_UNAVAILABLE;
import static com.android.billingclient.api.BillingClient.BillingResponse.OK;
import static com.android.billingclient.api.BillingClient.BillingResponse.USER_CANCELED;
import static com.android.billingclient.api.BillingClient.SkuType.INAPP;
import static com.zegoggles.smssync.App.TAG;
import static com.zegoggles.smssync.Consts.Billing.ALL_SKUS;
import static com.zegoggles.smssync.Consts.Billing.DONATION_PREFIX;
import static com.zegoggles.smssync.activity.donation.DonationActivity.DonationStatusListener.State.DONATED;
import static com.zegoggles.smssync.activity.donation.DonationActivity.DonationStatusListener.State.NOT_AVAILABLE;
import static com.zegoggles.smssync.activity.donation.DonationActivity.DonationStatusListener.State.NOT_DONATED;
import static com.zegoggles.smssync.activity.donation.DonationActivity.DonationStatusListener.State.UNKNOWN;

public class DonationActivity extends Activity implements SkuDetailsResponseListener, PurchasesUpdatedListener {

    private static boolean DEBUG_IAB = BuildConfig.DEBUG;

    private com.android.billingclient.api.BillingClient billingClient;

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        billingClient = new BillingClient.Builder(this).setListener(this).build();
        billingClient.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(int resultCode) {
                Log.d(TAG, "onBillingSetupFinished(" + resultCode + ")" + Thread.currentThread().getName());
                switch (resultCode) {
                    case OK:
                        queryAvailableSkus();
                        break;
                    default:
                        String message = getString(BILLING_UNAVAILABLE);
                        Toast.makeText(DonationActivity.this, message, Toast.LENGTH_LONG).show();
                        Log.w(TAG, "Problem setting up in-app billing: " + resultCode);
                        finish();
                        break;
                }
            }
            @Override
            public void onBillingServiceDisconnected() {
                Log.d(TAG, "onBillingServiceDisconnected");
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (billingClient != null) {
            billingClient = null;
        }
    }

    @Override
    public void onSkuDetailsResponse(SkuDetails.SkuDetailsResult result) {
        log("onQueryInventoryFinished(" + result + ", " + result + ")");

        if (result.getResponseCode() != OK) {
            Log.w(TAG, "failed to query inventory: " + result);
            return;
        }

        List<SkuDetails> skuDetailsList = new ArrayList<SkuDetails>();
        for (SkuDetails d : result.getSkuDetailsList()) {
            if (d.getSku().startsWith(DONATION_PREFIX)) {
                skuDetailsList.add(d);
            }
        }

        if (!isFinishing()) {
            showSelectDialog(skuDetailsList);
        } else {
            finish();
        }
    }

    private void queryAvailableSkus() {
        List<String> moreSkus = new ArrayList<String>();
        Collections.addAll(moreSkus, ALL_SKUS);
        billingClient.querySkuDetailsAsync(INAPP, moreSkus, DonationActivity.this);
    }

    private void showSelectDialog(List<SkuDetails> skuDetails) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);

        final List<SkuDetails> skus = new ArrayList<SkuDetails>(skuDetails);
        Collections.sort(skus, SkuComparator.INSTANCE);
        //noinspection ConstantConditions
        List<String> options = new ArrayList<String>();
        if (DEBUG_IAB) {
            for (TestSkus.TestSku testSku : TestSkus.SKUS) {
                options.add(testSku.getDescription());
            }
        }
        for (final SkuDetails sku : skus) {
            String item = sku.getTitle();
            if (!TextUtils.isEmpty(sku.getPrice())) {
                item += "  " + sku.getPrice();
            }
            options.add(item);
        }
        String[] items = new String[options.size()];
        options.toArray(items);
        builder.setItems(items, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String selectedSku;
                if (DEBUG_IAB) {
                    if (which < TestSkus.SKUS.length) {
                        selectedSku = TestSkus.SKUS[which].getSku();
                    } else {
                        selectedSku = skus.get(which - TestSkus.SKUS.length).getSku();
                    }
                } else {
                    selectedSku = skus.get(which).getSku();
                }
                billingClient.launchBillingFlow(DonationActivity.this,  new BillingFlowParams.Builder()
                        .setType(INAPP)
                        .setSku(selectedSku)
                        .build()
                );
            }
        });
        builder.setNegativeButton(android.R.string.no, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                finish();
            }
        });
        builder.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialogInterface) {
                finish();
            }
        });
        builder.setTitle(R.string.ui_dialog_donate_message).show();
    }

    @Override
    public void onPurchasesUpdated(int responseCode, List<Purchase> purchases) {
        log("onIabPurchaseFinished(" + responseCode + ", " + purchases);
        if (responseCode == OK) {
            Toast.makeText(this,
                    R.string.ui_donation_success_message,
                    Toast.LENGTH_LONG)
                    .show();
        } else {
            String message;
            switch (responseCode) {
                case ITEM_UNAVAILABLE:
                    message = getString(R.string.donation_error_unavailable);
                    break;
                case ITEM_ALREADY_OWNED:
                    message = getString(R.string.donation_error_already_owned);
                    break;
                case USER_CANCELED:
                    message = getString(R.string.donation_error_canceled);
                    break;
                default:
                    message = getString(R.string.donation_unspecified_error, responseCode);
            }
            Toast.makeText(this,
                    getString(R.string.ui_donation_failure_message, message),
                    Toast.LENGTH_LONG)
                    .show();
        }
        finish();
    }

    private void log(String s) {
        if (DEBUG_IAB) {
            Log.d(TAG, s);
        }
    }

    public interface DonationStatusListener {
        enum State {
            DONATED,
            NOT_DONATED,
            UNKNOWN,
            NOT_AVAILABLE
        }
        void userDonationState(State s);
    }

    public static void checkUserHasDonated(Context c, final DonationStatusListener l) {
        final BillingClient helper = new BillingClient.Builder(c).setListener(new PurchasesUpdatedListener() {
            @Override
            public void onPurchasesUpdated(int responseCode, List<Purchase> purchases) {
                Log.d(TAG, "onPurchasesUpdated("+responseCode+")");
            }
        }).build();
        helper.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(int resultCode) {
                Log.d(TAG, "checkUserHasDonated: onBillingSetupFinished("+resultCode+")");
                if (resultCode == OK) {
                    Purchase.PurchasesResult result = helper.queryPurchases(INAPP);
                    if (result.getResponseCode() == OK) {
                        final State s = userHasDonated(result.getPurchasesList()) ? DONATED : NOT_DONATED;
                        l.userDonationState(s);
                    } else {
                        l.userDonationState(UNKNOWN);
                    }
                } else {
                    l.userDonationState(resultCode == BILLING_UNAVAILABLE ? NOT_AVAILABLE : UNKNOWN);
                }
                helper.endConnection();
            }
            public void onBillingServiceDisconnected() {
            }
        });
    }

    private static boolean userHasDonated(List<Purchase> result) {
        for (String sku : ALL_SKUS) {
            for (Purchase purchase : result) {
                if (purchase.getSku().equals(sku)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static class SkuComparator implements Comparator<SkuDetails> {
        static final SkuComparator INSTANCE = new SkuComparator();

        @Override
        public int compare(SkuDetails lhs, SkuDetails rhs) {
            if (lhs.getPrice() != null && rhs.getPrice() != null) {
                return lhs.getPrice().compareTo(rhs.getPrice());
            } else if (lhs.getTitle() != null && rhs.getTitle() != null) {
                return lhs.getTitle().compareTo(rhs.getTitle());
            } else if (lhs.getSku() != null && rhs.getSku() != null) {
                return lhs.getSku().compareTo(rhs.getSku());
            } else {
                return 0;
            }
        }
    }
}

