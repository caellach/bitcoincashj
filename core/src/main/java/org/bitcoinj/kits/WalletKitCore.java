package org.bitcoinj.kits;

import com.google.common.collect.ImmutableList;
import com.google.common.io.Closeables;
import com.google.common.util.concurrent.*;
import org.bitcoinj.core.*;
import org.bitcoinj.core.listeners.DownloadProgressTracker;
import org.bitcoinj.core.slp.*;
import org.bitcoinj.core.slp.nft.NonFungibleSlpToken;
import org.bitcoinj.core.slp.opreturn.NftOpReturnOutputGenesis;
import org.bitcoinj.core.slp.opreturn.SlpOpReturnOutputGenesis;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.net.BlockingClientManager;
import org.bitcoinj.net.SlpDbNftDetails;
import org.bitcoinj.net.SlpDbTokenDetails;
import org.bitcoinj.net.discovery.DnsDiscovery;
import org.bitcoinj.net.discovery.PeerDiscovery;
import org.bitcoinj.protocols.payments.slp.SlpPaymentSession;
import org.bitcoinj.script.Script;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.store.SPVBlockStore;
import org.bitcoinj.wallet.*;
import org.bouncycastle.crypto.params.KeyParameter;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.*;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.channels.FileLock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

public class WalletKitCore extends AbstractIdleService {
    protected static final Logger log = LoggerFactory.getLogger(WalletAppKit.class);
    protected volatile Context context;
    protected NetworkParameters params;
    protected Script.ScriptType preferredOutputScriptType;
    protected KeyChainGroupStructure structure;

    protected WalletProtobufSerializer.WalletFactory walletFactory;
    @Nullable
    protected DeterministicSeed restoreFromSeed;
    @Nullable
    protected DeterministicKey restoreFromKey;
    protected File directory;
    protected volatile File vWalletFile;
    protected String filePrefix;

    protected boolean useAutoSave = true;
    protected DownloadProgressTracker downloadListener;
    protected boolean blockingStartup = true;
    protected boolean autoStop = true;
    protected InputStream checkpoints;
    protected String userAgent, version;
    protected PeerAddress[] peerAddresses;
    protected volatile BlockChain vChain;
    protected volatile SPVBlockStore vStore;
    protected volatile Wallet vWallet;
    protected volatile PeerGroup vPeerGroup;
    @Nullable
    protected PeerDiscovery discovery;

    public boolean useTor = false;
    public String torProxyIp = "127.0.0.1";
    public String torProxyPort = "9050";

    /** SLP common stuff **/
    protected ArrayList<SlpUTXO> slpUtxos = new ArrayList<>();
    protected ArrayList<SlpToken> slpTokens = new ArrayList<>();
    protected ArrayList<SlpTokenBalance> slpBalances = new ArrayList<>();
    protected ArrayList<String> verifiedSlpTxs = new ArrayList<>();
    protected ArrayList<SlpUTXO> nftUtxos = new ArrayList<>();
    protected ArrayList<NonFungibleSlpToken> nfts = new ArrayList<>();
    protected ArrayList<SlpTokenBalance> nftBalances = new ArrayList<>();
    protected ArrayList<SlpUTXO> nftParentUtxos = new ArrayList<>();
    protected ArrayList<SlpTokenBalance> nftParentBalances = new ArrayList<>();

    /**
     * Sets a wallet factory which will be used when the kit creates a new wallet.
     */
    public WalletKitCore setWalletFactory(WalletProtobufSerializer.WalletFactory walletFactory) {
        this.walletFactory = walletFactory;
        return this;
    }

    /**
     * If a seed is set here then any existing wallet that matches the file name will be renamed to a backup name,
     * the chain file will be deleted, and the wallet object will be instantiated with the given seed instead of
     * a fresh one being created. This is intended for restoring a wallet from the original seed. To implement restore
     * you would shut down the existing appkit, if any, then recreate it with the seed given by the user, then start
     * up the new kit. The next time your app starts it should work as normal (that is, don't keep calling this each
     * time).
     */
    public WalletKitCore restoreWalletFromSeed(DeterministicSeed seed) {
        this.restoreFromSeed = seed;
        return this;
    }

    /**
     * If an account key is set here then any existing wallet that matches the file name will be renamed to a backup name,
     * the chain file will be deleted, and the wallet object will be instantiated with the given key instead of
     * a fresh seed being created. This is intended for restoring a wallet from an account key. To implement restore
     * you would shut down the existing appkit, if any, then recreate it with the key given by the user, then start
     * up the new kit. The next time your app starts it should work as normal (that is, don't keep calling this each
     * time).
     */
    public WalletKitCore restoreWalletFromKey(DeterministicKey accountKey) {
        this.restoreFromKey = accountKey;
        return this;
    }

    /**
     * <p>Override this to return wallet extensions if any are necessary.</p>
     *
     * <p>When this is called, chain(), store(), and peerGroup() will return the created objects, however they are not
     * initialized/started.</p>
     */
    protected List<WalletExtension> provideWalletExtensions() throws Exception {
        return ImmutableList.of();
    }

    public ArrayList<SlpTokenBalance> getSlpBalances() {
        return this.slpBalances;
    }

    public ArrayList<SlpTokenBalance> getNftBalances() {
        return this.nftBalances;
    }

    public ArrayList<SlpUTXO> getNftParentUtxos() {
        return this.nftParentUtxos;
    }

    public ArrayList<SlpTokenBalance> getNftParentBalances() {
        return this.nftParentBalances;
    }

    public ArrayList<SlpToken> getSlpTokens() {
        return this.slpTokens;
    }

    public ArrayList<SlpUTXO> getSlpUtxos() {
        return this.slpUtxos;
    }

    public ArrayList<SlpUTXO> getNftUtxos() {
        return this.nftUtxos;
    }

    public SlpAddress currentSlpReceiveAddress() {
        return this.wallet().currentReceiveAddress().toSlp();
    }

    public SlpAddress currentSlpChangeAddress() {
        return this.wallet().currentChangeAddress().toSlp();
    }

    public SlpAddress freshSlpReceiveAddress() {
        return this.wallet().freshReceiveAddress().toSlp();
    }

    public SlpAddress freshSlpChangeAddress() {
        return this.wallet().freshChangeAddress().toSlp();
    }

    public SlpToken getSlpToken(String tokenId) {
        for (SlpToken slpToken : this.slpTokens) {
            String slpTokenTokenId = slpToken.getTokenId();
            if (slpTokenTokenId != null) {
                if (slpTokenTokenId.equals(tokenId)) {
                    return slpToken;
                }
            }
        }

        return null;
    }

    public NonFungibleSlpToken getNft(String tokenId) {
        for (NonFungibleSlpToken slpToken : this.nfts) {
            String slpTokenTokenId = slpToken.getTokenId();
            if (slpTokenTokenId != null) {
                if (slpTokenTokenId.equals(tokenId)) {
                    return slpToken;
                }
            }
        }

        return null;
    }

    public NetworkParameters params() {
        return params;
    }

    public void setUseTor(boolean status) {
        this.useTor = status;
    }

    public void setTorProxyIp(String ip) {
        this.torProxyIp = ip;
    }

    public void setTorProxyPort(String port) {
        this.torProxyPort = port;
    }

    public BlockChain chain() {
        checkState(state() == Service.State.STARTING || state() == Service.State.RUNNING, "Cannot call until startup is complete");
        return vChain;
    }

    public BlockStore store() {
        checkState(state() == Service.State.STARTING || state() == Service.State.RUNNING, "Cannot call until startup is complete");
        return vStore;
    }

    public Wallet wallet() {
        checkState(state() == Service.State.STARTING || state() == Service.State.RUNNING, "Cannot call until startup is complete");
        return vWallet;
    }

    public PeerGroup peerGroup() {
        checkState(state() == Service.State.STARTING || state() == Service.State.RUNNING, "Cannot call until startup is complete");
        return vPeerGroup;
    }

    public File directory() {
        return directory;
    }

    /**
     * Will only connect to the given addresses. Cannot be called after startup.
     */
    public WalletKitCore setPeerNodes(PeerAddress... addresses) {
        checkState(state() == State.NEW, "Cannot call after startup");
        this.peerAddresses = addresses;
        return this;
    }

    /**
     * Will only connect to localhost. Cannot be called after startup.
     */
    public WalletKitCore connectToLocalHost() {
        try {
            final InetAddress localHost = InetAddress.getLocalHost();
            return setPeerNodes(new PeerAddress(params, localHost, params.getPort()));
        } catch (UnknownHostException e) {
            // Borked machine with no loopback adapter configured properly.
            throw new RuntimeException(e);
        }
    }

    /**
     * If true, the wallet will save itself to disk automatically whenever it changes.
     */
    public WalletKitCore setAutoSave(boolean value) {
        checkState(state() == State.NEW, "Cannot call after startup");
        useAutoSave = value;
        return this;
    }

    /**
     * If you want to learn about the sync process, you can provide a listener here. For instance, a
     * {@link DownloadProgressTracker} is a good choice. This has no effect unless setBlockingStartup(false) has been called
     * too, due to some missing implementation code.
     */
    public WalletKitCore setDownloadListener(DownloadProgressTracker listener) {
        this.downloadListener = listener;
        return this;
    }

    /**
     * If true, will register a shutdown hook to stop the library. Defaults to true.
     */
    public WalletKitCore setAutoStop(boolean autoStop) {
        this.autoStop = autoStop;
        return this;
    }

    /**
     * If set, the file is expected to contain a checkpoints file calculated with BuildCheckpoints. It makes initial
     * block sync faster for new users - please refer to the documentation on the bitcoinj website
     * (https://bitcoinj.github.io/speeding-up-chain-sync) for further details.
     */
    public WalletKitCore setCheckpoints(InputStream checkpoints) {
        if (this.checkpoints != null)
            Closeables.closeQuietly(checkpoints);
        this.checkpoints = checkNotNull(checkpoints);
        return this;
    }

    /**
     * If true (the default) then the startup of this service won't be considered complete until the network has been
     * brought up, peer connections established and the block chain synchronised. Therefore {@link #awaitRunning()} can
     * potentially take a very long time. If false, then startup is considered complete once the network activity
     * begins and peer connections/block chain sync will continue in the background.
     */
    public WalletKitCore setBlockingStartup(boolean blockingStartup) {
        this.blockingStartup = blockingStartup;
        return this;
    }

    /**
     * Sets the string that will appear in the subver field of the version message.
     *
     * @param userAgent A short string that should be the name of your app, e.g. "My Wallet"
     * @param version   A short string that contains the version number, e.g. "1.0-BETA"
     */
    public WalletKitCore setUserAgent(String userAgent, String version) {
        this.userAgent = checkNotNull(userAgent);
        this.version = checkNotNull(version);
        return this;
    }

    /**
     * Sets the peer discovery class to use. If none is provided then DNS is used, which is a reasonable default.
     */
    public WalletKitCore setDiscovery(@Nullable PeerDiscovery discovery) {
        this.discovery = discovery;
        return this;
    }

    /**
     * Tests to see if the spvchain file has an operating system file lock on it. Useful for checking if your app
     * is already running. If another copy of your app is running and you start the appkit anyway, an exception will
     * be thrown during the startup process. Returns false if the chain file does not exist or is a directory.
     */
    public boolean isChainFileLocked() throws IOException {
        RandomAccessFile file2 = null;
        try {
            File file = new File(directory, filePrefix + ".spvchain");
            if (!file.exists())
                return false;
            if (file.isDirectory())
                return false;
            file2 = new RandomAccessFile(file, "rw");
            FileLock lock = file2.getChannel().tryLock();
            if (lock == null)
                return true;
            lock.release();
            return false;
        } finally {
            if (file2 != null)
                file2.close();
        }
    }

    @Override
    protected void startUp() throws Exception {
        // Runs in a separate thread.
        Context.propagate(context);
        if (!directory.exists()) {
            if (!directory.mkdirs()) {
                throw new IOException("Could not create directory " + directory.getAbsolutePath());
            }
        }
        log.info("Starting up with directory = {}", directory);
        try {
            File chainFile = new File(directory, filePrefix + ".spvchain");
            boolean chainFileExists = chainFile.exists();
            vWalletFile = new File(directory, filePrefix + ".wallet");
            boolean shouldReplayWallet = (vWalletFile.exists() && !chainFileExists) || restoreFromSeed != null || restoreFromKey != null;
            vWallet = createOrLoadWallet(shouldReplayWallet);

            // Initiate Bitcoin network objects (block store, blockchain and peer group)
            vStore = new SPVBlockStore(params, chainFile);
            if (!chainFileExists || restoreFromSeed != null || restoreFromKey != null) {
                if (checkpoints == null && !Utils.isAndroidRuntime()) {
                    checkpoints = CheckpointManager.openStream(params);
                }

                if (checkpoints != null) {
                    // Initialize the chain file with a checkpoint to speed up first-run sync.
                    long time;
                    if (restoreFromSeed != null) {
                        time = restoreFromSeed.getCreationTimeSeconds();
                        if (chainFileExists) {
                            log.info("Clearing the chain file in preparation for restore.");
                            vStore.clear();
                        }
                    } else if (restoreFromKey != null) {
                        time = restoreFromKey.getCreationTimeSeconds();
                        if (chainFileExists) {
                            log.info("Clearing the chain file in preparation for restore.");
                            vStore.clear();
                        }
                    } else {
                        time = vWallet.getEarliestKeyCreationTime();
                    }
                    if (time > 0)
                        CheckpointManager.checkpoint(params, checkpoints, vStore, time);
                    else
                        log.warn("Creating a new uncheckpointed block store due to a wallet with a creation time of zero: this will result in a very slow chain sync");
                } else if (chainFileExists) {
                    log.info("Clearing the chain file in preparation for restore.");
                    vStore.clear();
                }
            }
            vChain = new BlockChain(params, vStore);
            vPeerGroup = createPeerGroup();
            if (this.userAgent != null)
                vPeerGroup.setUserAgent(userAgent, version);

            // Set up peer addresses or discovery first, so if wallet extensions try to broadcast a transaction
            // before we're actually connected the broadcast waits for an appropriate number of connections.
            if (peerAddresses != null) {
                for (PeerAddress addr : peerAddresses) vPeerGroup.addAddress(addr);
                vPeerGroup.setMaxConnections(peerAddresses.length);
                peerAddresses = null;
            } else if (!params.getId().equals(NetworkParameters.ID_REGTEST)) {
                vPeerGroup.addPeerDiscovery(discovery != null ? discovery : new DnsDiscovery(params));
            }
            vChain.addWallet(vWallet);
            vPeerGroup.addWallet(vWallet);
            onSetupCompleted();

            if (blockingStartup) {
                vPeerGroup.start();
                // Make sure we shut down cleanly.
                installShutdownHook();

                // TODO: Be able to use the provided download listener when doing a blocking startup.
                final DownloadProgressTracker listener = new DownloadProgressTracker();
                vPeerGroup.startBlockChainDownload(listener);
                listener.await();
            } else {
                Futures.addCallback(vPeerGroup.startAsync(), new FutureCallback() {
                    @Override
                    public void onSuccess(@Nullable Object result) {
                        final DownloadProgressTracker l = downloadListener == null ? new DownloadProgressTracker() : downloadListener;
                        vPeerGroup.startBlockChainDownload(l);
                    }

                    @Override
                    public void onFailure(Throwable t) {
                        throw new RuntimeException(t);

                    }
                }, MoreExecutors.directExecutor());
            }
        } catch (BlockStoreException e) {
            throw new IOException(e);
        }
    }

    @Override
    protected void shutDown() throws Exception {
        // Runs in a separate thread.
        try {
            Context.propagate(context);
            vPeerGroup.stop();
            vWallet.saveToFile(vWalletFile);
            vStore.close();

            vPeerGroup = null;
            vWallet = null;
            vStore = null;
            vChain = null;
        } catch (BlockStoreException e) {
            throw new IOException(e);
        }
    }

    protected Wallet createOrLoadWallet(boolean shouldReplayWallet) throws Exception {
        Wallet wallet;

        maybeMoveOldWalletOutOfTheWay();

        if (vWalletFile.exists()) {
            wallet = loadWallet(shouldReplayWallet);
        } else {
            wallet = createWallet();
            wallet.currentReceiveAddress();
            for (WalletExtension e : provideWalletExtensions()) {
                wallet.addExtension(e);
            }

            // Currently the only way we can be sure that an extension is aware of its containing wallet is by
            // deserializing the extension (see WalletExtension#deserializeWalletExtension(Wallet, byte[]))
            // Hence, we first save and then load wallet to ensure any extensions are correctly initialized.
            wallet.saveToFile(vWalletFile);
            wallet = loadWallet(false);
        }

        if (useAutoSave) {
            this.setupAutoSave(wallet);
        }

        return wallet;
    }

    protected void setupAutoSave(Wallet wallet) {
        wallet.autosaveToFile(vWalletFile, 5, TimeUnit.SECONDS, null);
    }

    private Wallet loadWallet(boolean shouldReplayWallet) throws Exception {
        Wallet wallet;
        FileInputStream walletStream = new FileInputStream(vWalletFile);
        try {
            List<WalletExtension> extensions = provideWalletExtensions();
            WalletExtension[] extArray = extensions.toArray(new WalletExtension[extensions.size()]);
            Protos.Wallet proto = WalletProtobufSerializer.parseToProto(walletStream);
            final WalletProtobufSerializer serializer;
            if (walletFactory != null)
                serializer = new WalletProtobufSerializer(walletFactory);
            else
                serializer = new WalletProtobufSerializer();
            wallet = serializer.readWallet(params, this.structure.accountPathFor(this.preferredOutputScriptType), extArray, proto);
            if (shouldReplayWallet)
                wallet.reset();
        } finally {
            walletStream.close();
        }
        return wallet;
    }

    protected Wallet createWallet() {
        KeyChainGroup.Builder kcg = KeyChainGroup.builder(params, structure);
        if (restoreFromSeed != null)
            kcg.fromSeed(restoreFromSeed, preferredOutputScriptType);
        else if (restoreFromKey != null)
            kcg.addChain(DeterministicKeyChain.builder().spend(restoreFromKey).outputScriptType(preferredOutputScriptType).build());
        else
            kcg.fromRandom(preferredOutputScriptType);
        if (walletFactory != null) {
            return walletFactory.create(params, kcg.build());
        } else {
            return new Wallet(params, kcg.build()); // default
        }
    }

    private void maybeMoveOldWalletOutOfTheWay() {
        if (restoreFromSeed == null && restoreFromKey == null) return;
        if (!vWalletFile.exists()) return;
        int counter = 1;
        File newName;
        do {
            newName = new File(vWalletFile.getParent(), "Backup " + counter + " for " + vWalletFile.getName());
            counter++;
        } while (newName.exists());
        log.info("Renaming old wallet file {} to {}", vWalletFile, newName);
        if (!vWalletFile.renameTo(newName)) {
            // This should not happen unless something is really messed up.
            throw new RuntimeException("Failed to rename wallet for restore");
        }
    }

    /**
     * This method is invoked on a background thread after all objects are initialised, but before the peer group
     * or block chain download is started. You can tweak the objects configuration here.
     */
    protected void onSetupCompleted() {
    }

    protected PeerGroup createPeerGroup() {
        if (useTor) {
            System.setProperty("socksProxyHost", torProxyIp);
            System.setProperty("socksProxyPort", torProxyPort);
            return new PeerGroup(this.vWallet.getParams(), this.vChain, new BlockingClientManager());
        } else {
            return new PeerGroup(this.vWallet.getParams(), this.vChain);
        }
    }

    protected void installShutdownHook() {
        if (autoStop) Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                try {
                    WalletKitCore.this.stopAsync();
                    WalletKitCore.this.awaitTerminated();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }

    public void calculateSlpBalance(SlpUTXO slpUTXO, SlpToken slpToken) {
        String tokenId = slpToken.getTokenId();
        double tokenAmount = BigDecimal.valueOf(slpUTXO.getTokenAmountRaw()).scaleByPowerOfTen(-slpToken.getDecimals()).doubleValue();
        if (this.isBalanceRecorded(tokenId)) {
            Objects.requireNonNull(this.getTokenBalance(tokenId)).addToBalance(tokenAmount);
        } else {
            this.slpBalances.add(new SlpTokenBalance(tokenId, tokenAmount));
        }
    }

    public void calculateNftBalance(SlpUTXO slpUTXO, NonFungibleSlpToken nft) {
        String tokenId = nft.getTokenId();
        double tokenAmount = BigDecimal.valueOf(slpUTXO.getTokenAmountRaw()).scaleByPowerOfTen(-nft.getDecimals()).doubleValue();
        if (this.isNftBalanceRecorded(tokenId)) {
            Objects.requireNonNull(this.getNftTokenBalance(tokenId)).addToBalance(tokenAmount);
        } else {
            this.nftBalances.add(new SlpTokenBalance(tokenId, tokenAmount));
        }
    }

    public void calculateNftParentBalance(SlpUTXO slpUTXO, SlpToken nftParentToken) {
        String tokenId = nftParentToken.getTokenId();
        double tokenAmount = BigDecimal.valueOf(slpUTXO.getTokenAmountRaw()).scaleByPowerOfTen(-nftParentToken.getDecimals()).doubleValue();
        if (this.isNftParentBalanceRecorded(tokenId)) {
            Objects.requireNonNull(this.getNftParentBalance(tokenId)).addToBalance(tokenAmount);
        } else {
            this.nftParentBalances.add(new SlpTokenBalance(tokenId, tokenAmount));
        }
    }

    public boolean isBalanceRecorded(String tokenId) {
        for (SlpTokenBalance tokenBalance : this.slpBalances) {
            if (tokenBalance.getTokenId().equals(tokenId)) {
                return true;
            }
        }

        return false;
    }

    public SlpTokenBalance getTokenBalance(String tokenId) {
        for (SlpTokenBalance tokenBalance : this.slpBalances) {
            if (tokenBalance.getTokenId().equals(tokenId)) {
                return tokenBalance;
            }
        }

        return null;
    }

    public boolean isNftBalanceRecorded(String tokenId) {
        for (SlpTokenBalance tokenBalance : this.nftBalances) {
            if (tokenBalance.getTokenId().equals(tokenId)) {
                return true;
            }
        }

        return false;
    }

    public SlpTokenBalance getNftTokenBalance(String tokenId) {
        for (SlpTokenBalance tokenBalance : this.nftBalances) {
            if (tokenBalance.getTokenId().equals(tokenId)) {
                return tokenBalance;
            }
        }

        return null;
    }

    public boolean isNftParentBalanceRecorded(String tokenId) {
        for (SlpTokenBalance tokenBalance : this.nftParentBalances) {
            if (tokenBalance.getTokenId().equals(tokenId)) {
                return true;
            }
        }

        return false;
    }

    public SlpTokenBalance getNftParentBalance(String tokenId) {
        for (SlpTokenBalance tokenBalance : this.nftParentBalances) {
            if (tokenBalance.getTokenId().equals(tokenId)) {
                return tokenBalance;
            }
        }

        return null;
    }

    public boolean tokenIsMapped(String tokenId) {
        for (SlpToken slpToken : this.slpTokens) {
            String slpTokenTokenId = slpToken.getTokenId();
            if (slpTokenTokenId != null) {
                if (slpTokenTokenId.equals(tokenId)) {
                    return true;
                }
            }
        }

        return false;
    }

    public boolean nftIsMapped(String tokenId) {
        for (NonFungibleSlpToken slpToken : this.nfts) {
            String slpTokenTokenId = slpToken.getTokenId();
            if (slpTokenTokenId != null) {
                if (slpTokenTokenId.equals(tokenId)) {
                    return true;
                }
            }
        }

        return false;
    }

    public boolean hasTransactionBeenRecorded(String txid) {
        return this.verifiedSlpTxs.contains(txid);
    }

    public Transaction createSlpTransaction(String slpDestinationAddress, String tokenId, double numTokens, @Nullable KeyParameter aesKey) throws InsufficientMoneyException {
        return this.createSlpTransaction(slpDestinationAddress, tokenId, numTokens, aesKey, true);
    }

    public Transaction createSlpTransaction(String slpDestinationAddress, String tokenId, double numTokens, @Nullable KeyParameter aesKey, boolean allowUnconfirmed) throws InsufficientMoneyException {
        return SlpTxBuilder.buildTx(tokenId, numTokens, slpDestinationAddress, this, aesKey, allowUnconfirmed).blockingGet();
    }

    public Transaction createSlpTransactionBip70(String tokenId, @Nullable KeyParameter aesKey, List<Long> rawTokens, List<String> addresses, SlpPaymentSession paymentSession) throws InsufficientMoneyException {
        return this.createSlpTransactionBip70(tokenId, aesKey, rawTokens, addresses, paymentSession, true);
    }

    public Transaction createSlpTransactionBip70(String tokenId, @Nullable KeyParameter aesKey, List<Long> rawTokens, List<String> addresses, SlpPaymentSession paymentSession, boolean allowUnconfirmed) throws InsufficientMoneyException {
        return SlpTxBuilder.buildTxBip70(tokenId, this, aesKey, rawTokens, addresses, paymentSession, allowUnconfirmed).blockingGet();
    }

    public SendRequest createSlpGenesisTransaction(String ticker, String name, String url, int decimals, long tokenQuantity, @Nullable KeyParameter aesKey) throws InsufficientMoneyException {
        SendRequest req = SendRequest.createSlpTransaction(this.params());
        req.aesKey = aesKey;
        req.shuffleOutputs = false;
        req.feePerKb = Coin.valueOf(1000L);
        req.ensureMinRequiredFee = true;
        SlpOpReturnOutputGenesis slpOpReturn = new SlpOpReturnOutputGenesis(ticker, name, url, decimals, tokenQuantity);
        req.tx.addOutput(Coin.ZERO, slpOpReturn.getScript());
        req.tx.addOutput(this.wallet().getParams().getMinNonDustOutput(), this.wallet().currentChangeAddress());
        return req;
    }

    public SendRequest createNftChildGenesisTransaction(String nftParentId, String ticker, String name, String url, @Nullable KeyParameter aesKey) throws InsufficientMoneyException {
        return createNftChildGenesisTransaction(nftParentId, ticker, name, url, aesKey, true);
    }

    public SendRequest createNftChildGenesisTransaction(String nftParentId, String ticker, String name, String url, @Nullable KeyParameter aesKey, boolean allowUnconfirmed) throws InsufficientMoneyException {
        SendRequest req = SlpTxBuilder.buildNftChildGenesisTx(nftParentId, ticker, name, url, this, aesKey, allowUnconfirmed);
        return req;
    }

    public Transaction createNftChildSendTx(String slpDestinationAddress, String nftTokenId, double numTokens, @Nullable KeyParameter aesKey) throws InsufficientMoneyException {
        return this.createNftChildSendTx(slpDestinationAddress, nftTokenId, numTokens, aesKey, true);
    }

    public Transaction createNftChildSendTx(String slpDestinationAddress, String nftTokenId, double numTokens, @Nullable KeyParameter aesKey, boolean allowUnconfirmed) throws InsufficientMoneyException {
        return SlpTxBuilder.buildNftChildSendTx(nftTokenId, numTokens, slpDestinationAddress, this, aesKey, allowUnconfirmed).blockingGet();
    }
}
