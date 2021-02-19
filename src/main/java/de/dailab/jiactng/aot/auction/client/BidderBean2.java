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

public class BidderBean2 extends AbstractAgentBean {

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

    private int auctionRound;

    private ICommunicationAddress auctionAddressA;


    //	Auction A
    private ArrayList<Item> initialItemsAuctionA;
    private int initialNumItemsAuctionA;
    private Map<Resource, Double> minValMap;

    private int expectedSellRoundToAdd;
    private int expectedSellAllRound;
    private int roundIncrease = 5;
    private int expectedMaxReturn = roundIncrease * expectedSellRoundToAdd;
    private double riskAversity = 1.3;

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
        itemCoeffs.put(Resource.A, 0.87 * expectedMaxReturn);
        itemCoeffs.put(Resource.B, 0.66 * expectedMaxReturn);
        itemCoeffs.put(Resource.C, 0.94 * expectedMaxReturn);
        itemCoeffs.put(Resource.D, 0.94 * expectedMaxReturn);
        itemCoeffs.put(Resource.E, 0.97 * expectedMaxReturn);
        itemCoeffs.put(Resource.F, 0.93 * expectedMaxReturn);
        itemCoeffs.put(Resource.J, (double) expectedMaxReturn);
        itemCoeffs.put(Resource.K, (double) expectedMaxReturn);

        this.memory.attach(new MessageObserver(), new JiacMessage());

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
                            if (callForBids.getAuctioneerId() == auctionIdB) {
                                processCallB(callForBids, message.getSender());
                            } else if (callForBids.getAuctioneerId() == auctionIdC) {
                                processCallC(callForBids, message.getSender());
                            }
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

    private void processStartAuctions(StartAuctions startAuctions, ICommunicationAddress address) {
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
                auctionRound = 0;
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

    private void processCallA(CallForBids callForBids, ICommunicationAddress address) {
        List<Resource> bundle = callForBids.getBundle();
        double val = calculateBundleMaxVal(bundle, true);
        if (val < callForBids.getMinOffer()) {
            return;
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
        } else if (informBuy.getType() == InformBuy.BuyType.LOST) {
            log.info("LOST: " + informBuy.getBundle());
            wallet.updateCredits(informBuy.getPrice());
        } else if (informBuy.getType() == InformBuy.BuyType.INVALID) {
            log.info("InvalidBuy");
        }
    }

    //  Auction B

    private void processCallB(CallForBids callForBids, ICommunicationAddress address) {
        String resourceVal = joinResources(callForBids.getBundle());
        callMap.put(resourceVal, callForBids);
        allCallsProcessed.put(resourceVal, true);
        if (allCallsProcessed.values().stream().allMatch(s -> s)) {
            auctionRound++;
//            log.info(wallet.toString() + " V2");
            setValuesPerB();
            for (CallForBids anyBid : callMap.values()) {
                if (auctionRound <= expectedSellAllRound) {
                    if (joinResources(anyBid.getBundle()).equals("FF") && wallet.get(Resource.E) >= 3
                            && wallet.get(Resource.F) <= 2) {
                        continue;
                    }
                    if (wallet.getCredits() <= 2000 && wallet.contains(anyBid.getBundle())) {
                        double val = calculateBundleMaxVal(anyBid.getBundle(), false);
                        val /= riskAversity;

                        if (val <= anyBid.getMinOffer()) {
                            wallet.remove(anyBid.getBundle());
                            Bid bid = new Bid(auctionIdB, bidderId, anyBid.getCallId(), anyBid.getMinOffer());
                            sendPayload(bid, address);
                            //  Sell only one bundle.
                            break;
                        }
                    }
                }
                else {
                    if (wallet.contains(anyBid.getBundle())) {
                        wallet.remove(anyBid.getBundle());
                        Bid bid = new Bid(auctionIdB, bidderId, anyBid.getCallId(), anyBid.getMinOffer());
                        sendPayload(bid, address);
                    }
                }
            }
            //  Set all false.
            for (String s : valOfBundlesB.keySet()) {
                allCallsProcessed.put(s, false);
            }
        }
    }

    private void processInformSell(InformSell informSell) {
        if (informSell.getType() == InformSell.SellType.SOLD) {
            log.info("SOLD: " + informSell.getBundle());
            wallet.updateCredits(informSell.getPrice());
        } else if (informSell.getType() == InformSell.SellType.NOT_SOLD) {
            wallet.add(informSell.getBundle());
        } else {
            log.info("Invalid Sell");
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

    private double calculateBundleMaxVal(List<Resource> bundle, boolean buy) {
        double val = 0;
        for (Resource resource : Resource.values()) {
            if (resource != Resource.G) {
                int amount = (int) bundle.stream().filter(res -> res.equals(resource)).count();
                try {
                    if (buy) {
                        val += amount * (projectedMaxValItem.get(resource) + itemCoeffs.get(resource));
                    } else {
                        val += amount * projectedMaxValItem.get(resource);
                    }
                } catch (NullPointerException e) {
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

    //  After seeing 20 C's were left for some reason..
    public double getRatio(double x, double y) {
        double ratio = x / y;
        if (ratio < 0.7) {
            ratio = 0.7;
        } else if (ratio > 1.5) {
            ratio = 1.5;
        }
        return ratio;
    }

    private void processCallC(CallForBids bids, ICommunicationAddress address) {
        Offer offer;
        if (auctionRound > 100) {
            int amountJ = wallet.get(Resource.J);
            int amountK = wallet.get(Resource.K);
            if (amountJ > 0 || amountK > 0) {
                Resource resource = amountJ > amountK ? Resource.J : Resource.K;
                double val = projectedMaxValItem.get(resource);
                List<Resource> resourceList = new ArrayList<Resource>();
                resourceList.add(resource);
                offer = new Offer(auctionIdC, bidderId, resourceList, val);
                sendPayload(offer, address);
            }
            else {
                Random random = new Random();
                int randVal = random.nextInt(5);
                Resource resource;
                switch (randVal) {
                    case 0:
                        resource = Resource.A;
                        break;
                    case 1:
                        resource = Resource.B;
                        break;
                    case 2:
                        resource = Resource.C;
                        break;
                    case 3:
                        resource = Resource.D;
                        break;
                    case 4:
                        resource = Resource.E;
                        break;
                    case 5:
                        resource = Resource.F;
                        break;
                    default:
                        resource = Resource.A;
                        break;
                }
                if (wallet.get(resource) > 0) {
                    double val = projectedMaxValItem.get(resource) + itemCoeffs.get(resource);
                    List<Resource> resourceList = new ArrayList<Resource>();
                    resourceList.add(resource);
                    offer = new Offer(auctionIdC, bidderId, resourceList, val);
                    sendPayload(offer, address);
                }
            }
        }
    }

    private void sendPayload(IFact payload, ICommunicationAddress address) {
//        log.info(String.format("Sending %s to %s", payload, address.getName()));
        JiacMessage message = new JiacMessage(payload);
        IActionDescription sendAction = retrieveAction(ICommunicationBean.ACTION_SEND);
        invoke(sendAction, new Serializable[]{message, address});
    }

    private String joinResources(List<Resource> bundle) {
        List<String> resourceStringList = bundle.stream().map(s -> s.toString()).collect(Collectors.toList());
        String value = String.join("", resourceStringList);

        return value;
    }

    public void setBidderId(String bidderId) {
        this.bidderId = bidderId;
    }

    public void setGroupToken(String groupToken) {
        this.groupToken = groupToken;
    }

    public void setMessageGroup(String messageGroup) {
        this.messageGroup = messageGroup;
    }

    public void setExpectedSellRoundToAdd(int expectedSellRoundToAdd) {
        this.expectedSellRoundToAdd = expectedSellRoundToAdd;
    }

    public void setExpectedSellAllRound(int expectedSellAllRound) {
        this.expectedSellAllRound = expectedSellAllRound;
    }
}
