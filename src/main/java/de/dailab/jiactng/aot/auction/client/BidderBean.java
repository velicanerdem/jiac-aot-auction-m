package de.dailab.jiactng.aot.auction.client;

import de.dailab.jiactng.agentcore.AbstractAgentBean;
import de.dailab.jiactng.agentcore.comm.CommunicationAddressFactory;
import de.dailab.jiactng.agentcore.comm.ICommunicationAddress;
import de.dailab.jiactng.agentcore.comm.ICommunicationBean;
import de.dailab.jiactng.agentcore.comm.IGroupAddress;
import de.dailab.jiactng.agentcore.comm.message.JiacMessage;
import de.dailab.jiactng.agentcore.knowledge.IFact;
import de.dailab.jiactng.agentcore.ontology.IActionDescription;
import de.dailab.jiactng.aot.auction.onto.*;
import org.sercho.masp.space.event.SpaceEvent;
import org.sercho.masp.space.event.SpaceObserver;
import org.sercho.masp.space.event.WriteCallEvent;

import java.io.Serializable;
import java.util.*;
import java.util.stream.Collectors;

public class BidderBean extends AbstractAgentBean {

    private String bidderId;
    private String groupToken;
    private String messageGroup;
    private IGroupAddress messageGroupAddress;

    private Wallet wallet;

    //	For once initialization
    private Integer auctionsID;

    //	Auctions

    private int auctionIdA;
    private int auctionIdB;
    private int auctionIdC;

    private ICommunicationAddress auctionAddressA;


    //	Auction A
    private ArrayList<Item> initialItemsAuctionA;
    private int initialNumItemsAuctionA;
    private Map<Resource, Double> minValMap;

    double riskAversity = 1.2;

    private double multiplierForAdaptive = 0.9;
    private double powerForAdaptive = 3.;
    private double divisorForAdaptive = Math.pow(multiplierForAdaptive, 1 / powerForAdaptive);
    private Map<Resource, Double> itemCoeffs;

    //	Not sure to use
    //  Unused.
    private int numItems;
    private Map<Resource, Integer> numResources;

//    private double profitMean;
//    List<Double> profitSample;

    //	Auction B
    Map<String, Double> valOfBundlesB;
    //  For calculating the profit possible.
    Map<Resource, Double> projectedMaxValItem;
    //  For calculating the loss possible.
    Map<Resource, Double> projectedMinValItem;
    Map<String, Boolean> allCallsProcessed;
    Map<String, CallForBids> callMap;

    double ftoEVal = 7 / 24;


    @Override
    public void doStart() throws Exception {
        super.doStart();

        itemCoeffs = new HashMap<>();
        itemCoeffs.put(Resource.A, 100.);
        itemCoeffs.put(Resource.B, 25.);
        itemCoeffs.put(Resource.C, 150.);
        itemCoeffs.put(Resource.D, 150.);
        itemCoeffs.put(Resource.E, 300.);
        itemCoeffs.put(Resource.F, 75.);
        itemCoeffs.put(Resource.G, -20.);

        this.memory.attach(new MessageObserver(), new JiacMessage());

        System.out.println(messageGroup);
        messageGroupAddress = CommunicationAddressFactory.createGroupAddress(messageGroup);

        auctionsID = null;

        numResources = new HashMap<>();
        for (Resource resource : Resource.values()) {
            numResources.put(resource, 0);
        }

        log.info("Initialization started.");
        IActionDescription act = retrieveAction(ICommunicationBean.ACTION_JOIN_GROUP);

        invoke(act, new Serializable[]{messageGroupAddress});
        log.info("Joined group.");
    }

    /*
     * TODO
     * when the agent starts, create a message observer and attach it to the
     * agent's memory. that message observer should then handle the different
     * messages and send a suitable Bid in reply. see the readme and the
     * sequence diagram for the expected order of messages.
     */

    class MessageObserver implements SpaceObserver<IFact> {
        @Override
        public void notify(SpaceEvent<? extends IFact> spaceEvent) {
            if (spaceEvent instanceof WriteCallEvent) {
                JiacMessage message = (JiacMessage) ((WriteCallEvent) spaceEvent).getObject();
                IFact payload = message.getPayload();
                ICommunicationAddress sender = message.getSender();
                if (payload instanceof StartAuctions) {
                    if (auctionsID == null) {
                        StartAuctions startAuctions = (StartAuctions) payload;
                        processStartAuctions(startAuctions, sender);
                    }
                } else if (payload instanceof StartAuction) {
                    StartAuction startAuction = (StartAuction) payload;
                    processStartAuction(startAuction, sender);
                }
                if (payload instanceof InitializeBidder) {
                    InitializeBidder initializeBidder = (InitializeBidder) payload;
                    if (initializeBidder.getBidderId().equals(bidderId)) {
                        wallet = initializeBidder.getWallet();
                    }
                } else if (auctionAddressA != null) {
                    if (payload instanceof CallForBids) {
                        CallForBids callForBids = (CallForBids) payload;
                        if (callForBids.getMode() == CallForBids.CfBMode.BUY) {
                            processCallA(callForBids, message.getSender());
                        } else if (callForBids.getMode() == CallForBids.CfBMode.SELL) {
                            processCallB(callForBids, message.getSender());
                        }
                    } else if (payload instanceof InformBuy) {
                        InformBuy informBuy = (InformBuy) payload;
                        processInformBuy(informBuy);
                    } else if (payload instanceof InformSell) {
                        InformSell informSell = (InformSell) payload;
                        processInformSell(informSell);
                    }
                }
            }
        }
    }

    void processStartAuctions(StartAuctions startAuctions, ICommunicationAddress address) {
        auctionsID = startAuctions.getAuctionsId();
        log.info(startAuctions.getMessage());

        Register register = new Register(bidderId, groupToken);
        sendPayload(register, address);
    }

    private void processStartAuction(StartAuction startAuction, ICommunicationAddress address) {
        StartAuction.Mode mode = startAuction.getMode();

        switch (mode) {
            case A:
                auctionIdA = startAuction.getAuctioneerId();
                auctionAddressA = address;
                numItems = startAuction.getNumItems();
                initialNumItemsAuctionA = numItems;
                initialItemsAuctionA = new ArrayList<>(startAuction.getInitialItems());
                break;
            case B:
                auctionIdB = startAuction.getAuctioneerId();
                valOfBundlesB = new HashMap<>();
                projectedMaxValItem = new HashMap<>();
                projectedMinValItem = new HashMap<>();
                allCallsProcessed = new HashMap<>();
                callMap = new HashMap<>();
                for (Item item : startAuction.getInitialItems()) {
                    processBBundleVals(item.getBundle(), item.getPrice());
                }
                setValuesPerB();
                break;
            case C:
                auctionIdC = startAuction.getAuctioneerId();
                break;
        }
    }

    private double calculateBundleValue(List<Resource> bundle) {
        double val = 0;

        for (Resource resource : Resource.values()) {
            //  filter gives NullPointerException for some reason.
            int amount = 0;
            for (Resource resource1 : bundle) {
                if (resource == resource1) {
                    amount++;
                }
            }
            double itemVal = getValue(resource);
            val += amount * itemVal;
        }
        return val;
    }

    private double calculateBundleProfit(List<Resource> bundle) {
        double profit = 0;
        for (Resource resource : Resource.values()) {
            int amount = 0;
            for (Resource resource1 : bundle) {
                if (resource == resource1) {
                    amount++;
                }
            }
            double itemVal = getValue(resource);
            profit += amount * (projectedMaxValItem.get(resource) - itemVal);
        }
        return profit;
    }

    private void processCallA(CallForBids callForBids, ICommunicationAddress address) {
        List<Resource> bundle = callForBids.getBundle();
        double val = calculateBundleValue(bundle);

        if (val < callForBids.getMinOffer()) {
            val = callForBids.getMinOffer();
        }

        double maxVal = calculateBundleMaxVal(bundle) / riskAversity;
        if (maxVal < val) {
            val = maxVal;
        }

        if (wallet.getCredits() >= val) {
            Bid bid = new Bid(auctionIdA, bidderId, callForBids.getCallId(), val);
            wallet.updateCredits(-val);
            sendPayload(bid, address);
        }
    }

    private void processInformBuy(InformBuy informBuy) {
        if (informBuy.getType() == InformBuy.BuyType.WON) {
            log.info("BOUGHT: " + informBuy.getBundle());
            wallet.add(informBuy.getBundle());
            for (Resource resource : Resource.values()) {
                if (resource != Resource.G) {
                    if (informBuy.getBundle().contains(resource)) {
                        itemCoeffs.compute(resource, (key, val) -> val * multiplierForAdaptive);
                    }
                }
            }
        } else if (informBuy.getType() == InformBuy.BuyType.LOST) {
            log.info("LOST: " + informBuy.getBundle());
            wallet.updateCredits(informBuy.getPrice());
            for (Resource resource : Resource.values()) {
                if (resource != Resource.G) {
                    if (informBuy.getBundle().contains(resource)) {
                        itemCoeffs.compute(resource, (key, val) -> val / divisorForAdaptive);
                    }
                }
            }
        } else if (informBuy.getType() == InformBuy.BuyType.INVALID) {
            log.info("InvalidBuy");
        }
    }

    private void sendPayload(IFact payload, ICommunicationAddress address) {
//        log.info(String.format("Sending %s to %s", payload, address.getName()));
        JiacMessage message = new JiacMessage(payload);
        IActionDescription sendAction = retrieveAction(ICommunicationBean.ACTION_SEND);
        invoke(sendAction, new Serializable[]{message, address});
    }

    private double getValue(Resource resource) {
        switch (resource) {
            case A:
                return resAValue();
            case B:
                return resBValue();
            case C:
                return resCDValue(Resource.C);
            case D:
                return resCDValue(Resource.D);
            case E:
                return resEFValue(Resource.E);
            case F:
                return resEFValue(Resource.F);
            default:
                return -20;
        }
    }

    private double resAValue() {
        return itemCoeffs.get(Resource.A);
    }

    private double resBValue() {
        return itemCoeffs.get(Resource.B);
    }

    private double resCDValue(Resource resource) {
        //	Normalization in case of 0.
        int numC = wallet.get(Resource.C) + 1;
        int numD = wallet.get(Resource.D) + 1;
        double val;
        if (resource == Resource.C) {
            val = getRatio(numD, numC);
        } else {
            val = getRatio(numC, numD);
        }
        val = val * val;
        val *= itemCoeffs.get(resource);
        return val;
    }

    private double resEFValue(Resource resource) {
        //	Normalization in case of 0.
        int numE = wallet.get(Resource.E) + 1;
        int numF = wallet.get(Resource.F) + 1;

        double numEVal = numE * ftoEVal;
        double val;
        if (resource == Resource.E) {
            val = getRatio(numF, numEVal);
        } else {
            val = getRatio(numEVal, numF);
        }

        val = val * val;
        val *= itemCoeffs.get(resource);
        return val;
    }

    //  Auction B

    private void processCallB(CallForBids callForBids, ICommunicationAddress address) {
        String resourceVal = joinResources(callForBids.getBundle());
        callMap.put(resourceVal, callForBids);
        allCallsProcessed.put(resourceVal, true);
        if (allCallsProcessed()) {
            log.info(wallet.toString());
            setValuesPerB();
            for (CallForBids anyBid : callMap.values()) {
//                log.info("E number: " + wallet.get(Resource.E).toString());
                if (joinResources(anyBid.getBundle()).equals("FF") && wallet.get(Resource.E) >= 3) {
                    continue;
                }
                if (wallet.contains(anyBid.getBundle())) {
                    if (anyBid.getMinOffer() >= calculateBundleValue(anyBid.getBundle()) * riskAversity) {
                        double multiplier = 0.8;

                        double val = calculateBundleMaxVal(anyBid.getBundle());
                        val = val * multiplier;

                        if (val <= anyBid.getMinOffer()) {
                            wallet.remove(anyBid.getBundle());
                            Bid bid = new Bid(auctionIdB, bidderId, anyBid.getCallId(), anyBid.getMinOffer());
                            sendPayload(bid, address);
                        }
                        else {
                            log.info("Bundle will not be sold: " + anyBid.getBundle().toString());
                        }
                    }

                }
            }
            setAllCallsProcessedFalse();
        }
    }

    private void processInformSell(InformSell informSell) {
        if (informSell.getType() == InformSell.SellType.SOLD) {
            log.info("SOLD: " + informSell.getBundle());
            wallet.updateCredits(informSell.getPrice());
        } else if (informSell.getType() == InformSell.SellType.NOT_SOLD) {
            wallet.add(informSell.getBundle());
        } else {
            log.info("Invalid Sell: " + informSell.getBundle().toString());
        }
    }

    private void processBBundleVals(List<Resource> resources, double price) {
        String value = joinResources(resources);

        valOfBundlesB.put(value, price);
    }

    private void setValuesPerB() {
        setAValues();
        setBValues();
        setCDValues();
        setEFValues();
        setJKValues();
    }

    private double calculateBundleMaxVal(List<Resource> bundle) {
        double val = 0;
        for (Resource resource : Resource.values()) {
            if (resource != Resource.G) {
                int amount = (int) bundle.stream().filter(res -> res.equals(resource)).count();
                try {
                    val += amount * projectedMaxValItem.get(resource);
                }
                catch (NullPointerException e) {
                    log.info(resource);
                }
            } else {
                val -= 20;
            }
        }
        return val;
    }

    private double calculateBundleMinVal(List<Resource> bundle) {
        double val = 0;
        for (Resource resource : Resource.values()) {
            if (resource != Resource.G) {
                int amount = (int) bundle.stream().filter(res -> res.equals(resource)).count();
                val += amount * projectedMinValItem.get(resource);
            } else {
                val -= 20;
            }
        }
        return val;
    }

    private void setAValues() {
        double valTwo = valOfBundlesB.get("AA") / 2;
        double valThree = valOfBundlesB.get("AAA") / 3;
        double valFour = valOfBundlesB.get("AAAA") / 4;

        List<Double> doubleList = Arrays.asList(valTwo, valThree, valFour);

        double maxVal = Collections.max(doubleList);
        projectedMaxValItem.put(Resource.A, maxVal);

        double minVal = Collections.min(doubleList);
        projectedMinValItem.put(Resource.A, minVal);
    }

    private void setBValues() {
        //  After A
        double valTwo = valOfBundlesB.get("BB") / 2;
        double valPerA = valOfBundlesB.get("AAB") - 2 * projectedMaxValItem.get(Resource.A);

        List<Double> doubleList = Arrays.asList(valTwo, valPerA);

        double maxVal = Collections.max(doubleList);
        projectedMaxValItem.put(Resource.B, maxVal);

        double minVal = Collections.min(doubleList);
        projectedMinValItem.put(Resource.B, minVal);
    }

    private void setCDValues() {
        double valC = valOfBundlesB.get("CCCDDD") / 6;
        double valPerA = (valOfBundlesB.get("CCDDAA") - 2 * projectedMaxValItem.get(Resource.A)) / 4;
        double valPerB = (valOfBundlesB.get("CCDDBB") - 2 * projectedMaxValItem.get(Resource.B)) / 4;

        List<Double> doubleList = Arrays.asList(valC, valPerA, valPerB);

        double maxVal = Collections.max(doubleList);
        //  Just to make sure its compatible both of them are set.
        projectedMaxValItem.put(Resource.C, maxVal);
        projectedMaxValItem.put(Resource.D, maxVal);

        double minVal = Collections.min(doubleList);
        projectedMinValItem.put(Resource.C, minVal);
        projectedMinValItem.put(Resource.D, minVal);
    }

    private void setEFValues() {
        //  (1/3 + 1/4) / 2

        double valTwo = valOfBundlesB.get("EEF") / (2 + ftoEVal);
        double valThree = valOfBundlesB.get("EEEF") / (3 + ftoEVal);
        double valFour = valOfBundlesB.get("EEEEF") / (4 + ftoEVal);
        double valFive = valOfBundlesB.get("EEEEEF") / (5 + ftoEVal);

        List<Double> doubleList = Arrays.asList(valTwo, valThree, valFour, valFive);

        double maxVal = Collections.max(doubleList);
        projectedMaxValItem.put(Resource.E, maxVal);

        double minVal = Collections.min(doubleList);
        projectedMinValItem.put(Resource.E, minVal);

        double valF = valOfBundlesB.get("FF") / 2;

        double maxValF = Math.max(valF, maxVal * ftoEVal);
        projectedMaxValItem.put(Resource.F, maxValF);

        double minValF = Math.max(maxValF, maxVal * ftoEVal);
        projectedMinValItem.put(Resource.F, minVal);
    }

    private void setJKValues() {
        //  After others end.
        double valPerA = valOfBundlesB.get("AJK") - projectedMaxValItem.get(Resource.A) / 2;
        double valPerF = valOfBundlesB.get("FJK") - projectedMaxValItem.get(Resource.F) / 2;

        double sumOfResVals = 0;
        for (Resource resource : Resource.values()) {
            if (resource != Resource.G && resource != Resource.J && resource != Resource.K) {
                sumOfResVals += projectedMaxValItem.get(resource);
            }
        }
        double valPerAll = valOfBundlesB.get("ABCDEFJK") - sumOfResVals;
        valPerAll /= 2;
        projectedMaxValItem.put(Resource.J, valPerAll);
        projectedMaxValItem.put(Resource.K, valPerAll);

        //  Better for these to be equal right now, but the name is confusing.
        projectedMinValItem.put(Resource.J, valPerAll);
        projectedMinValItem.put(Resource.K, valPerAll);
    }

//    private double calculateStandardDeviation(List<Double> sd) {
//
//        double newSum = 0;
//
//        for (int j = 0; j < sd.size(); j++) {
//            // put the calculation right in there
//            newSum = newSum + ((sd.get(j) - profitMean) * (sd.get(j) - profitMean));
//        }
//        double squaredDiffMean = (newSum) / (sd.size());
//        double standardDev = (Math.sqrt(squaredDiffMean));
//
//        return standardDev;
//    }

//	private double calculateBundleValuePerAllBidders(List<Resource> bundle) {
//		//	Value when bundle is sold.
//		double val = 0;
//
//		Map<Resource, Integer> count = new HashMap<>();
//		for (Resource resource: Resource.values()) {
//			//	Forgo slight inefficiency
//			int amount = (int) bundle.stream().filter(res -> res.equals(resource)).count();
//			if (!count.containsKey(resource)) {
//				count.put(resource, amount);
//			}
//		}
//
//		for (Resource resource: bundle) {
//			double _demand = estimatedTotalRequired.get(resource);
//			int _supply = numResources.get(resource);
//
//			_demand -= - count.get(resource);
//			_supply -= count.get(resource);
//
//			val += _demand / _supply;
//		}
//		double final_val = val * numItems / totalItemRequired * wallet.getCredits();
//
//		return final_val;
//	}

    //  After seeing 20 C's were left for some reason..
    public double getRatio(double x, double y) {
        double ratio = x/y;
        if (ratio < 0.7) {
            ratio = 0.7;
        }
        else if (ratio > 1.5) {
            ratio = 1.5;
        }
        return ratio;
    }

    public String getBidderId() {
        return bidderId;
    }

    public void setBidderId(String bidderId) {
        this.bidderId = bidderId;
    }

    public String getGroupToken() {
        return groupToken;
    }

    public void setGroupToken(String groupToken) {
        this.groupToken = groupToken;
    }

    public IGroupAddress getMessageGroupAddress() {
        return messageGroupAddress;
    }

    public void setMessageGroupAddress(IGroupAddress messageGroupAddress) {
        this.messageGroupAddress = messageGroupAddress;
    }

    public Integer getAuctionsID() {
        return auctionsID;
    }

    public void setAuctionsID(Integer auctionsID) {
        this.auctionsID = auctionsID;
    }

    public ArrayList<Item> getInitialItemsAuctionA() {
        return initialItemsAuctionA;
    }

    public void setInitialItemsAuctionA(ArrayList<Item> initialItemsAuctionA) {
        this.initialItemsAuctionA = initialItemsAuctionA;
    }

    public int getInitialNumItemsAuctionA() {
        return initialNumItemsAuctionA;
    }

    public void setInitialNumItemsAuctionA(int initialNumItemsAuctionA) {
        this.initialNumItemsAuctionA = initialNumItemsAuctionA;
    }

    public Map<Resource, Double> getMinValMap() {
        return minValMap;
    }

    public void setMinValMap(Map<Resource, Double> minValMap) {
        this.minValMap = minValMap;
    }

    public int getNumItems() {
        return numItems;
    }

    public void setNumItems(int numItems) {
        this.numItems = numItems;
    }

    public Map<Resource, Integer> getNumResources() {
        return numResources;
    }

    public void setNumResources(Map<Resource, Integer> numResources) {
        this.numResources = numResources;
    }

    public Wallet getWallet() {
        return wallet;
    }

    public void setWallet(Wallet wallet) {
        this.wallet = wallet;
    }

    public String getMessageGroup() {
        return messageGroup;
    }

    public void setMessageGroup(String messageGroup) {
        this.messageGroup = messageGroup;
    }

    private void setAllCallsProcessedFalse() {
        for (String s : valOfBundlesB.keySet()) {
            allCallsProcessed.put(s, false);
        }
    }

    private boolean allCallsProcessed() {
        for (Boolean b : allCallsProcessed.values()) {
            if (!b) {
                return false;
            }
        }
        return true;
    }

    private String joinResources(List<Resource> bundle) {
        List<String> resourceStringList = bundle.stream().map(s -> s.toString()).collect(Collectors.toList());
        String value = String.join("", resourceStringList);

        return value;
    }
}
