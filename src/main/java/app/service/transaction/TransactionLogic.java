package app.service.transaction;

import app.entity.Wallet;
import app.repository.WalletRepository;
import crypto.CPXKey;
import crypto.CryptoService;
import crypto.UInt256;
import message.request.cmd.GetAccountCmd;
import message.request.cmd.SendRawTransactionCmd;
import message.response.ExecResult;
import message.transaction.FixedNumber;
import message.transaction.ISerialize;
import message.transaction.Transaction;
import message.transaction.TransactionType;
import message.transaction.payload.*;
import message.util.GenericJacksonWriter;
import message.util.RequestCallerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.security.interfaces.ECPrivateKey;
import java.time.Instant;
import java.util.Optional;
import java.util.stream.Stream;

@Primary
@Service("TransactionLogic")
public class TransactionLogic implements IProposalTx, ITransferTx, IProducerTx {

    private final Logger log = LoggerFactory.getLogger(TransactionLogic.class);

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private RequestCallerService requestCaller;

    @Autowired
    private GenericJacksonWriter jacksonWriter;

    @Autowired
    private CryptoService cryptoService;

    @Value("${app.core.rpc}")
    private String rpcUrl;

    @Override
    public void voteOnProposal(final String producer, final String password,
                               final String proposalID, final boolean value) {
        try {
            final UInt256 proposal = new UInt256(proposalID);
            final ProposalVote vote = ProposalVote.builder()
                    .version(1)
                    .proposalId(proposal)
                    .vote(value)
                    .build();
            executeDefaultCallTx(producer, password, vote, ProposalVote.SCRIPT_HASH);
        } catch (Exception e){
            log.warn("Vote failed with: " + e.getMessage());
            Stream.of(e.getStackTrace()).forEach(stackTraceElement -> log.warn(stackTraceElement.toString()));
        }
    }

    @Override
    public void createNewProposal(final String producer, final String password, final int type,
                                  final double amount, final long timestamp) {
        try{
            final Proposal proposal;
            switch (type){
                case 1:
                    proposal = Proposal.builder()
                            .version(1)
                            .activeTime(timestamp)
                            .value(new FixedNumber(amount, FixedNumber.CPX))
                            .type(ProposalType.BLOCK_AWARD)
                            .build();
                    break;
                case 2:
                    proposal = Proposal.builder()
                            .version(1)
                            .activeTime(timestamp)
                            .value(new FixedNumber(amount, FixedNumber.CPX))
                            .type(ProposalType.TX_MIN_GAS)
                            .build();
                    break;
                case 3:
                    proposal = Proposal.builder()
                            .version(1)
                            .activeTime(timestamp)
                            .value(new FixedNumber(amount, FixedNumber.CPX))
                            .type(ProposalType.TX_GAS_LIMIT)
                            .build();
                    break;
                default:
                    proposal = Proposal.builder().build();
            }
            executeDefaultCallTx(producer, password, proposal, Proposal.SCRIPT_HASH);
        } catch (Exception e){
            log.warn("Proposal failed with: " + e.getMessage());
            Stream.of(e.getStackTrace()).forEach(stackTraceElement -> log.warn(stackTraceElement.toString()));
        }
    }

    @Override
    public void transfer(final String from, final String to, final double amount, final double gasPrice,
                         final double gasLimit, final String password) {
        try {
            CPXKey.getScriptHashFromCPXAddress(to);
            executeTx(from, password, () -> new byte[0],
                    new FixedNumber(amount, FixedNumber.CPX),
                    new FixedNumber(gasPrice, FixedNumber.KGP),
                    new FixedNumber(gasLimit, FixedNumber.KP),
                    TransactionType.TRANSFER, CPXKey.getScriptHashFromCPXAddress(to));
        } catch (Exception e) {
            log.error("This is not a valid address: " + to);
        }
    }

    @Override
    public void register(String from, double gasPrice, long gasLimit, String password,
                         String registerAddress, String type, String company, String url,
                         String country, String location, Integer longitude, Integer latitude) {
        try {
            final Registration registration = Registration.builder()
                    .version(1)
                    .fromPubKeyHash(CPXKey.getScriptHashFromCPXAddress(registerAddress))
                    .operationType(type.equals("add") ? OperationType.REGISTER : OperationType.REGISTER_CANCEL)
                    .country(country != null ? country : "")
                    .url(url != null ? url : "")
                    .name(company != null ? company : "")
                    .address(location != null ? location : "")
                    .longitude(longitude != null ? longitude : 0)
                    .latitude(latitude != null ? latitude : 0)
                    .voteCounts(new FixedNumber(0, FixedNumber.P))
                    .register(true)
                    .frozen(false)
                    .genesisWitness(false)
                    .ownerPubKeyHash(CPXKey.getScriptHashFromCPXAddress(registerAddress))
                    .build();
            executeCallTxWithGasLimitAndGasPrice(from, password, registration,
                    new FixedNumber(gasPrice, FixedNumber.KGP),
                    new FixedNumber(gasLimit, FixedNumber.KP),
                    Registration.SCRIPT_HASH);
        } catch (Exception e){
            log.warn("Registration failed with: " + e.getMessage());
            Stream.of(e.getStackTrace()).forEach(stackTraceElement -> log.warn(stackTraceElement.toString()));
        }
    }

    @Override
    public void voteOnProducer(String from, double gasPrice, long gasLimit, String password,
                               String candidate, double votes, String type) {
        try {
            final Vote vote = Vote.builder()
                    .amount(new FixedNumber(votes, FixedNumber.CPX))
                    .operationType(type.equals("add") ? OperationType.REGISTER : OperationType.REGISTER_CANCEL)
                    .voterPubKeyHash(CPXKey.getScriptHashFromCPXAddress(candidate))
                    .build();
            executeCallTxWithGasLimitAndGasPrice(from, password, vote,
                    new FixedNumber(gasPrice, FixedNumber.KGP),
                    new FixedNumber(gasLimit, FixedNumber.KP),
                    Vote.SCRIPT_HASH);
        } catch (Exception e){
            log.warn("Vote failed with: " + e.getMessage());
            Stream.of(e.getStackTrace()).forEach(stackTraceElement -> log.warn(stackTraceElement.toString()));
        }
    }

    Optional<Long> getNextNonceForAddress(final String address) {
        try {
            final String accountString = requestCaller.postRequest(rpcUrl, new GetAccountCmd(address));
            log.info("Get Account was: " + accountString);
            final ExecResult resultAccount = jacksonWriter.getObjectFromString(ExecResult.class, accountString);
            log.info("Result was: " + resultAccount.result.toString() + "\nStatus: " + resultAccount.status);
            if(resultAccount.succeed) {
                final long nonce = ((Number) resultAccount.result.get("nextNonce")).longValue();
                return Optional.of(nonce);
            }
        } catch (Exception e){
            log.warn("Get Account failed with: " + e.getMessage());
            Stream.of(e.getStackTrace()).forEach(stackTraceElement -> log.warn(stackTraceElement.toString()));
        }
        return Optional.empty();
    }

    void executeDefaultCallTx(final String walletAddress, final String password, final ISerialize payload, final String toAddress) {
        executeTx(walletAddress, password, payload,
                new FixedNumber(0, FixedNumber.P),
                new FixedNumber(1, FixedNumber.KGP),
                new FixedNumber(500, FixedNumber.KP),
                TransactionType.CALL, toAddress);
    }

    void executeCallTxWithGasLimitAndGasPrice(final String walletAddress, final String password, final ISerialize payload,
                                          final FixedNumber gasPrice, final FixedNumber gasLimit, final String toAddress){
        executeTx(walletAddress, password, payload,
                new FixedNumber(0, FixedNumber.P),
                gasPrice, gasLimit, TransactionType.CALL, toAddress);
    }

    void executeTx(final String walletAddress, final String password, final ISerialize payload,
              final FixedNumber amount, final FixedNumber gasPrice, final FixedNumber gasLimit,
                   final TransactionType type, final String toAddress){
        final Optional<Wallet> wallet = walletRepository.findById(walletAddress);
        wallet.ifPresentOrElse(account -> {
            try {
                final ECPrivateKey key = (ECPrivateKey) cryptoService.loadKeyPairFromKeyStore(account.getKeystore(),
                        password, CryptoService.KEY_NAME).getPrivate();
                log.info("Private key loaded");
                final Optional<Long> nonceOpt = getNextNonceForAddress(walletAddress);
                if(nonceOpt.isPresent()) {
                    final long nonce = nonceOpt.get();
                    log.info("Nonce is " + nonce);
                    final Transaction tx = Transaction.builder()
                            .txType(type)
                            .fromPubKeyHash(CPXKey.getScriptHash(key))
                            .toPubKeyHash(toAddress)
                            .amount(amount)
                            .gasPrice(gasPrice)
                            .gasLimit(gasLimit)
                            .nonce(nonce)
                            .version(1)
                            .data(payload.getBytes())
                            .executeTime(Instant.now().toEpochMilli())
                            .build();
                    log.info("Executing transaction");
                    final SendRawTransactionCmd cmd = new SendRawTransactionCmd(cryptoService.signBytes(key, tx));
                    final String result = requestCaller.postRequest(rpcUrl, cmd);
                    log.info("Execute result was: " + result);
                }
            } catch (Exception e) {
                log.warn("Execute Tx failed with: " + e.getMessage());
                Stream.of(e.getStackTrace()).forEach(stackTraceElement -> log.warn(stackTraceElement.toString()));
            }
        }, () -> log.warn("Wallet for Producer " + walletAddress + " could not be loaded"));
    }
}
