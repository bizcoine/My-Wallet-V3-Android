package info.blockchain.wallet.util;

import android.content.Context;
import android.util.Pair;

import info.blockchain.wallet.payload.PayloadManager;
import info.blockchain.wallet.view.helpers.ToastCustom;

import org.bitcoinj.crypto.MnemonicException;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import piuk.blockchain.android.R;

public class BackupWalletUtil {

    private Context context = null;

    public BackupWalletUtil(Context context) {
        this.context = context;
    }

    /**
     * Return ordered list of integer, string pairs which can be used to confirm mnemonic.
     *
     * @return List<Pair<Integer,String>>
     */
    public List<Pair<Integer, String>> getConfirmSequence() {

        List<Pair<Integer, String>> toBeConfirmed = new ArrayList<Pair<Integer, String>>();
        String[] s = getMnemonic();
        SecureRandom random = new SecureRandom();
        List<Integer> seen = new ArrayList<Integer>();

        int sel = 0;
        int i = 0;
        while (i < 3) {
            if (i == 3) {
                break;
            }
            sel = random.nextInt(s.length);
            if (seen.contains(sel)) {
                continue;
            } else {
                seen.add(sel);
                i++;
            }
        }

        Collections.sort(seen);

        for (int ii = 0; ii < 3; ii++) {
            toBeConfirmed.add(new Pair<Integer, String>(seen.get(ii), s[seen.get(ii)]));
        }

        return toBeConfirmed;
    }

    /**
     * Return mnemonic in the form of a string array. Make sure double encryption access is activated before calling.
     *
     * @return String[]
     */
    public String[] getMnemonic() {
        // Wallet is not double encrypted
        if (!PayloadManager.getInstance().getPayload().isDoubleEncrypted()) {
            return getHDSeedAsMnemonic(true);
        }
        // User has already entered double-encryption password
        else if (PayloadManager.getInstance().getTempDoubleEncryptPassword().toString().length() > 0) {
            return getMnemonicForDoubleEncryptedWallet();
        }
        // access must be established before calling this function
        else {
            return null;
        }

    }

    private String[] getMnemonicForDoubleEncryptedWallet() {

        String[] mnemonic = PayloadManager.getInstance().getMnemonicForDoubleEncryptedWallet();

        if(mnemonic != null){
            return mnemonic;
        }else{
            ToastCustom.makeText(context, context.getString(R.string.double_encryption_password_error), ToastCustom.LENGTH_SHORT, ToastCustom.TYPE_ERROR);
            return null;
        }
    }

    private String[] getHDSeedAsMnemonic(boolean mnemonic) {

        String seed = null;

        try {

            seed = PayloadManager.getInstance().getHDMnemonic();

        } catch (IOException | MnemonicException.MnemonicLengthException e) {
            e.printStackTrace();
            ToastCustom.makeText(context, context.getString(R.string.hd_error), ToastCustom.LENGTH_LONG, ToastCustom.TYPE_ERROR);
        } finally {

            return seed.split("\\s+");

        }
    }
}
