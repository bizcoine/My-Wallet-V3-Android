package info.blockchain.wallet.send;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.spongycastle.util.encoders.Hex;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Pair;
import android.widget.Toast;
//import android.util.Log;

import com.google.bitcoin.core.AddressFormatException;
import com.google.bitcoin.core.ECKey;
import com.google.bitcoin.core.Sha256Hash;
import com.google.bitcoin.core.Transaction;
import com.google.bitcoin.core.TransactionInput;
import com.google.bitcoin.core.TransactionOutput;
import com.google.bitcoin.core.Wallet;
import com.google.bitcoin.core.Transaction.SigHash;
import com.google.bitcoin.params.MainNetParams;

import info.blockchain.wallet.payload.LegacyAddress;
import info.blockchain.wallet.OpCallback;
import info.blockchain.wallet.hd.HD_WalletFactory;
import info.blockchain.wallet.hd.HD_Address;
import info.blockchain.wallet.multiaddr.MultiAddrFactory;
import info.blockchain.wallet.payload.PayloadFactory;
import info.blockchain.wallet.util.Hash;
import info.blockchain.wallet.util.PrivateKeyFactory;
import info.blockchain.wallet.util.WebUtil;

public class SendFactory	{

	private static SendFactory instance = null;
	private static Context context = null;

//	private ProgressDialog progress = null;

	private SendFactory () { ; }

	private String[] from = null;
	private HashMap<String,String> froms = null;

    public static SendFactory getInstance(Context ctx) {
    	
    	context = ctx;
    	
    	if(instance == null)	{
    		instance = new SendFactory();
    	}
    	
    	return instance;
    }

    public void send(final int accountIdx, final String toAddress, final BigInteger amount, final LegacyAddress legacyAddress, final BigInteger fee, final String note, final OpCallback opc) {

    	final boolean isHD = accountIdx == -1 ? false : true;

//		final String[] from;
//		final HashMap<String,String> froms;
    	final String xpub;
		
		if(isHD) {
	    	xpub = PayloadFactory.getInstance().get().getHdWallet().getAccounts().get(accountIdx).getXpub();
			
			HashMap<String,List<String>> unspentOutputs = MultiAddrFactory.getInstance().getUnspentOuts();
			List<String> data = unspentOutputs.get(xpub);
			froms = new HashMap<String,String>();
			for(String f : data) {
				if(f != null) {
					String[] s = f.split(",");
//					Log.i("address path", s[1] + " " + s[0]);
					froms.put(s[1], s[0]);
				}
			}

			from = froms.keySet().toArray(new String[froms.keySet().size()]);
		}
		else {
			xpub = null;
			
			froms = new HashMap<String,String>();
			from = new String[1];
			from[0] = legacyAddress.getAddress();
		}

		final HashMap<String, BigInteger> receivers = new HashMap<String, BigInteger>();
		receivers.put(toAddress, amount);

		final Handler handler = new Handler();
/*		
		if(progress != null && progress.isShowing()) {
			progress.dismiss();
			progress = null;
		}
		progress = new ProgressDialog(context);
		progress.setTitle(R.string.app_name);
		progress.setMessage("Please wait...");
		progress.show();
*/
		new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					Looper.prepare();

					List<MyTransactionOutPoint> allUnspent = null;
					if(isHD) {
						allUnspent = getUnspentOutputPoints(true, new String[]{ xpub }, amount.add(fee));
					}
					else {
						allUnspent = getUnspentOutputPoints(false, from, amount.add(fee));
					}
					if(allUnspent == null) {
//						Log.i("SpendThread", "allUnspent == null");
					}
//					Log.i("allUnspent list size", "" + allUnspent.size());
					BigInteger bTotalUnspent = BigInteger.ZERO;
					for(MyTransactionOutPoint outp : allUnspent) {
						bTotalUnspent = bTotalUnspent.add(outp.getValue());
					}
					if(amount.compareTo(bTotalUnspent) == 1) {
						return;
					}
					Pair<Transaction, Long> pair = null;
//					pair = makeTransaction(isSimpleSend, unspent, receivingAddresses, feeAmount, changeAddress);
					String changeAddr = null;
					if(isHD) {
						int changeIdx = PayloadFactory.getInstance().get().getHdWallet().getAccounts().get(accountIdx).getNbChangeAddresses();
//						Log.i("change idx", "" + changeIdx);
						if(!PayloadFactory.getInstance().get().isDoubleEncrypted()) {
							changeAddr = HD_WalletFactory.getInstance(context).get().getAccount(accountIdx).getChange().getAddressAt(changeIdx).getAddressString();
						}
						else {
							changeAddr = HD_WalletFactory.getInstance(context).getWatchOnlyWallet().getAccount(accountIdx).getChange().getAddressAt(changeIdx).getAddressString();
						}
					}
					else {
						changeAddr = legacyAddress.getAddress();
					}
//					Log.i("change address", changeAddr);
					pair = makeTransaction(true, allUnspent, receivers, fee, changeAddr);
//					Log.i("pair is", pair == null ? "null" : "not null");
					// Transaction cancelled
					if(pair == null) {
//						Log.i("pair is", "null");
/*						
						if(progress != null && progress.isShowing()) {
							progress.dismiss();
							progress = null;
						}
*/
						opc.onFail();
						return;
					}
					Transaction tx = pair.first;
					Long priority = pair.second; 

					Wallet wallet = new Wallet(MainNetParams.get());
					for (TransactionInput input : tx.getInputs()) {
						byte[] scriptBytes = input.getOutpoint().getConnectedPubKeyScript();
						String address = new BitcoinScript(scriptBytes).getAddress().toString();
//						Log.i("address from script", address);
						ECKey walletKey = null;
						try {
							String privStr = null;
							if(isHD) {
								String path = froms.get(address);
								String[] s = path.split("/");
								HD_Address hd_address = null;
								if(!PayloadFactory.getInstance().get().isDoubleEncrypted()) {
									hd_address = HD_WalletFactory.getInstance(context).get().getAccount(accountIdx).getChain(Integer.parseInt(s[1])).getAddressAt(Integer.parseInt(s[2]));
								}
								else {
									hd_address = HD_WalletFactory.getInstance(context).getWatchOnlyWallet().getAccount(accountIdx).getChain(Integer.parseInt(s[1])).getAddressAt(Integer.parseInt(s[2]));
								}
//								Log.i("HD address", hd_address.getAddressString());
								privStr = hd_address.getPrivateKeyString();
//								Log.i("priv", privStr);
								walletKey = PrivateKeyFactory.getInstance().getKey(PrivateKeyFactory.WIF_COMPRESSED, privStr);
							}
							else {
								walletKey = legacyAddress.getECKey();
							}
						} catch (AddressFormatException afe) {
							// skip add Watch Only Bitcoin Address key because already accounted for later with tempKeys
							afe.printStackTrace();
							continue;
						}

						if(walletKey != null) {
							wallet.addKey(walletKey);
						}
						else {
							opc.onFail();
						}

					}

					// Now sign the inputs
					tx.signInputs(SigHash.ALL, wallet);
					String hexString = new String(Hex.encode(tx.bitcoinSerialize()));
					//if(hexString.length() > 16384) {
					if(hexString.length() > (100 * 1024)) {
						opc.onFail();
						throw new Exception("Blockchain wallet's cannot handle transactions over 100KB in size. Please try splitting your transaction");
					}

//					Log.i("SendFactory tx string", hexString);
					String response = WebUtil.getInstance().postURL(WebUtil.SPEND_DOMAIN + "pushtx", "tx=" + hexString);
//					Log.i("Send response", response);
					if(response.contains("Transaction Submitted")) {

						opc.onSuccess();

						if(note != null && note.length() > 0) {
							Map<String,String> notes = PayloadFactory.getInstance().get().getNotes();
							notes.put(tx.getHashAsString(), note);
							PayloadFactory.getInstance().get().setNotes(notes);
						}

						if(isHD) {
							// increment change address counter
							PayloadFactory.getInstance().get().getHdWallet().getAccounts().get(accountIdx).incChange();
						}

					}
					else {
			        	Toast.makeText(context, response, Toast.LENGTH_SHORT).show();
						opc.onFail();
					}
					
		        	/*						
					if(progress != null && progress.isShowing()) {
						progress.dismiss();
						progress = null;
					}
		        	 */

//					progress.onSend(tx, response);

					handler.post(new Runnable() {
						@Override
						public void run() {
							;
						}
					});

					Looper.loop();

				}
	        	catch(Exception e) {
	        		e.printStackTrace();
	        	}
			}
		}).start();
	}

	private List<MyTransactionOutPoint> getUnspentOutputPoints(boolean isHD, String[] from, BigInteger totalAmount) throws Exception {
		
		BigInteger totalValue = BigInteger.ZERO;

		String args = null;
		if(isHD) {
			args = from[0];
		}
		else {
			StringBuffer buffer = new StringBuffer();
			for(int i = 0; i < from.length; i++) {
				buffer.append(from[i]);
				if(i != (from.length - 1)) {
					buffer.append("|");
				}
			}
			
			args = buffer.toString();
		}

		String response = WebUtil.getInstance().getURL(WebUtil.UNSPENT_OUTPUTS_DOMAIN + "unspent?active=" + args);
//		Log.i("Unspent outputs", response);

		List<MyTransactionOutPoint> outputs = new ArrayList<MyTransactionOutPoint>();

		Map<String, Object> root = (Map<String, Object>)JSONValue.parse(response);
		List<Map<String, Object>> outputsRoot = (List<Map<String, Object>>)root.get("unspent_outputs");
		if(outputsRoot == null) {
			return null;
		}
		for (Map<String, Object> outDict : outputsRoot) {

			byte[] hashBytes = Hex.decode((String)outDict.get("tx_hash"));

			Hash hash = new Hash(hashBytes);
			hash.reverse();
			Sha256Hash txHash = new Sha256Hash(hash.getBytes());

			int txOutputN = ((Number)outDict.get("tx_output_n")).intValue();
			BigInteger value = BigInteger.valueOf(((Number)outDict.get("value")).longValue());
			totalValue = totalValue.add(value);
			byte[] scriptBytes = Hex.decode((String)outDict.get("script"));
			int confirmations = ((Number)outDict.get("confirmations")).intValue();

			if(isHD) {
				String address = new BitcoinScript(scriptBytes).getAddress().toString();
				String path = null;
				if(outDict.containsKey("xpub")) {
					JSONObject obj = (JSONObject)outDict.get("xpub");
					if(obj.containsKey("path")) {
						path = (String)obj.get("path");
						froms.put(address, path);
					}
				}
			}

			// Construct the output
			MyTransactionOutPoint outPoint = new MyTransactionOutPoint(txHash, txOutputN, value, scriptBytes);
			outPoint.setConfirmations(confirmations);
			outputs.add(outPoint);
			
			if(totalValue.compareTo(totalAmount) >= 0) {
				break;
			}

		}

		return outputs;
	}

	private Pair<Transaction, Long> makeTransaction(boolean isSimpleSend, List<MyTransactionOutPoint> unspent, HashMap<String, BigInteger> receivingAddresses, BigInteger fee, final String changeAddress) throws Exception {

		long priority = 0;

		if(unspent == null || unspent.size() == 0) {
//			throw new InsufficientFundsException("No free outputs to spend.");
			return null;
		}

		if(fee == null) {
			fee = BigInteger.ZERO;
		}

		//Construct a new transaction
		Transaction tx = new Transaction(MainNetParams.get());

		BigInteger outputValueSum = BigInteger.ZERO;

		for(Iterator<Entry<String, BigInteger>> iterator = receivingAddresses.entrySet().iterator(); iterator.hasNext();) {
			Map.Entry<String, BigInteger> mapEntry = iterator.next();
			String toAddress = mapEntry.getKey();
			BigInteger amount = mapEntry.getValue();

			if(amount == null || amount.compareTo(BigInteger.ZERO) <= 0) {
				throw new Exception("You must provide an amount");
			}

			outputValueSum = outputValueSum.add(amount);
			//Add the output
			BitcoinScript toOutputScript = BitcoinScript.createSimpleOutBitcoinScript(new BitcoinAddress(toAddress));
//			Log.d("MyRemoteWallet", "MyRemoteWallet makeTransaction toAddress: " + toAddress + "amount: " + amount);

			TransactionOutput output = new TransactionOutput(MainNetParams.get(), null, amount, toOutputScript.getProgram());

			tx.addOutput(output);
		}

		//Now select the appropriate inputs
		BigInteger valueSelected = BigInteger.ZERO;
		BigInteger valueNeeded =  outputValueSum.add(fee);
		BigInteger minFreeOutputSize = BigInteger.valueOf(1000000);

		MyTransactionOutPoint changeOutPoint = null;

		for(MyTransactionOutPoint outPoint : unspent) {

			BitcoinScript script = new BitcoinScript(outPoint.getScriptBytes());

			if(script.getOutType() == BitcoinScript.ScriptOutTypeStrange) {
				continue;
			}

			BitcoinScript inputScript = new BitcoinScript(outPoint.getConnectedPubKeyScript());
			String address = inputScript.getAddress().toString();

			//if isSimpleSend don't use address as input if is output 
			if(isSimpleSend && receivingAddresses.get(address) != null) {
				continue;
			}

			MyTransactionInput input = new MyTransactionInput(MainNetParams.get(), null, new byte[0], outPoint);
			
			input.outpoint = outPoint;
//			Log.d("MyRemoteWallet", "MyRemoteWallet makeTransaction fromAddress: " + address + "amount: " + outPoint.value);

			tx.addInput(input);

			valueSelected = valueSelected.add(outPoint.getValue());

			priority += outPoint.getValue().longValue() * outPoint.getConfirmations();

			if(changeAddress == null) {
				changeOutPoint = outPoint;
			}

			if(valueSelected.compareTo(valueNeeded) == 0 || valueSelected.compareTo(valueNeeded.add(minFreeOutputSize)) >= 0) {
				break;
			}
		}

		//Check the amount we have selected is greater than the amount we need
		if(valueSelected.compareTo(valueNeeded) < 0) {
//			throw new InsufficientFundsException("Insufficient Funds");
			return null;
		}

		BigInteger change = valueSelected.subtract(outputValueSum).subtract(fee);

		//Now add the change if there is any
		if (change.compareTo(BigInteger.ZERO) > 0) {
			BitcoinScript change_script;
			if (changeAddress != null) {
				change_script = BitcoinScript.createSimpleOutBitcoinScript(new BitcoinAddress(changeAddress));
//				Log.d("MyRemoteWallet", "MyRemoteWallet makeTransaction changeAddress != null: " + changeAddress + "change: " + change);
			} else if (changeOutPoint != null) {
				BitcoinScript inputScript = new BitcoinScript(changeOutPoint.getConnectedPubKeyScript());
//    			Log.d("MyRemoteWallet", "MyRemoteWallet makeTransaction changeAddress == null: " + inputScript.getAddress() + "change: " + change);

				//Return change to the first address
				change_script = BitcoinScript.createSimpleOutBitcoinScript(inputScript.getAddress());
			} else {
				throw new Exception("Invalid transaction attempt");
			}
			TransactionOutput change_output = new TransactionOutput(MainNetParams.get(), null, change, change_script.getProgram());

			tx.addOutput(change_output);				
		}

		long estimatedSize = tx.bitcoinSerialize().length + (114 * tx.getInputs().size());

		priority /= estimatedSize;

		return new Pair<Transaction, Long>(tx, priority);
	}

	private interface SendProgress {

		public void onStart();

		// Return false to cancel
//		public boolean onReady(Transaction tx, BigInteger fee, FeePolicy feePolicy, long priority);
		public boolean onReady(Transaction tx, BigInteger fee, long priority);
		public void onSend(Transaction tx, String message);

		// Return true to cancel the transaction or false to continue without it
		public ECKey onPrivateKeyMissing(String address);

		public void onError(String message);
		public void onProgress(String message);
	}

}