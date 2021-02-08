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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

/**
 * TODO Implement this class.
 * 
 * You might also decide to split the logic of your bidder up onto several
 * agent beans, e.g. one for each type of auction. In this case, remember
 * to keep the agent's `Wallet` in synch between the different roles, e.g.
 * using the agent's memory, as seen for the auctioneer beans.
 */
public class BidderBean extends AbstractAgentBean {

	private String bidderId;
	private String groupToken;
	private String messageGroup;
	private IGroupAddress messageGroupAddress;

	//	For once initialization
	private Integer auctionsID;

	private StartAuction.Mode mode;
	private int auctionID;

	private ArrayList<Item> initialItemsAuctionA;
	private int initialNumItemsAuctionA;
	private Map<Resource, Double> minValMap;
	private double riskAversityFactor = 0.7;

	private int numItems;

	private Map<Resource, Integer> numResources;

	private Wallet wallet;

	private int numBidders = 5;

//	private int totalItemRequired;
//	private Map<Resource, Double> estimatedTotalRequired;
//	private Map<Resource, Integer> itemCoeffs;

	/*
	 * TODO
	 * add properties for e.g. the multicast message group, or the bidderID
	 * add getter methods for those properties so they can be set in the
	 * Spring configuration file
	 */

	/*
	 * TODO
	 * when the agent starts, use the action ICommunicationBean.ACTION_JOIN_GROUP
	 * to join the multicast message group "de.dailab.jiactng.aot.auction"
	 * for the final competition, or a group of your choosing for testing
	 * make sure to use the same message group as the auctioneer!
	 */

	@Override
	public void doStart() throws Exception {
		super.doStart();

		this.memory.attach(new MessageObserver(), new JiacMessage());

		System.out.println(messageGroup);
		messageGroupAddress = CommunicationAddressFactory.createGroupAddress(messageGroup);

		auctionsID = null;

		numResources = new HashMap<>();
		for (Resource resource: Resource.values()) {
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

	class MessageObserver implements SpaceObserver<IFact>{
		@Override
		public void notify(SpaceEvent<? extends IFact> spaceEvent) {
			if (spaceEvent instanceof WriteCallEvent) {
				JiacMessage message = (JiacMessage) ((WriteCallEvent) spaceEvent).getObject();
				IFact payload = message.getPayload();
				if (payload instanceof StartAuctions) {
					if (auctionsID == null) {
						StartAuctions startAuctions = (StartAuctions) payload;
						processStartAuctions(startAuctions);
					}
				}
				if (payload instanceof StartAuction) {
					StartAuction startAuction = (StartAuction) payload;
					processStartAuction(startAuction);
				}
				else if (payload instanceof InitializeBidder) {
					InitializeBidder initializeBidder = (InitializeBidder) payload;
					if (initializeBidder.getBidderId().equals(bidderId)) {
						wallet = initializeBidder.getWallet();
						processWallet();
					}
				}
				else if (payload instanceof CallForBids) {
					CallForBids callForBids = (CallForBids) payload;
					processCall(callForBids);
				}
				else if (payload instanceof InformBuy) {
					InformBuy informBuy = (InformBuy) payload;
					//	Nothing now.
					processInformBuy(informBuy);
				}
			}
		}
	}

	/*
	 * TODO You will receive your initial "Wallet" from the auctioneer, but
	 * afterwards you will have to keep track of your spendings and acquisitions
	 * yourself. The Auctioneer will do so, as well.
	 */

	void processStartAuctions(StartAuctions startAuctions) {
		auctionsID = startAuctions.getAuctionsId();
		log.info(startAuctions.getMessage());

		Register register = new Register(bidderId, groupToken);
		sendPayload(register, messageGroupAddress);
	}

	private void processStartAuction(StartAuction startAuction) {
		mode = startAuction.getMode();
		auctionID = startAuction.getAuctioneerId();
		numItems = startAuction.getNumItems();


		if (mode == StartAuction.Mode.A) {
			initialNumItemsAuctionA = numItems;
			initialItemsAuctionA = new ArrayList<>(startAuction.getInitialItems());
			processItemsA();
		}
	}

	private void processItemsA() {
		for (Item item: initialItemsAuctionA) {
			for (Resource resource: item.getBundle()) {
				numResources.compute(resource, (key, val) -> val+1);
			}
		}
		minValMap = new HashMap<>();
		//	Calculate values of each item risk-averse.
		for (Resource resource: Resource.values()) {
			double relativeValue = numResources.get(resource) / initialNumItemsAuctionA;
			double value = relativeValue * wallet.getCredits() * riskAversityFactor;
			minValMap.put(resource, value);
		}
	}



	private double calculateBundleValue(List<Resource> bundle) {
		double val = 0;
		double minVal = 0;

		int totalItemsRequired = getTotalItemsRequired();

		Map<Resource, Integer> count = new HashMap<>();
		for (Resource resource: Resource.values()) {
			//	Forgo slight inefficiency
			int amount = (int) bundle.stream().filter(res -> res.equals(resource)).count();
			if (!count.containsKey(resource)) {
				count.put(resource, amount);
			}
		}

		numItems -= bundle.size();

		for (Resource resource: bundle) {
			double _demand = -1 * wallet.get(resource);
			double _supply = numResources.get(resource) / numBidders;

			int countOfItem = count.get(resource);

			//	Value after bundle is sold.
			_demand -= countOfItem;
			_supply -= countOfItem;

			val += _demand / _supply;

			minVal += countOfItem * minValMap.getOrDefault(resource, 0.);
		}
		val *= totalItemsRequired / numItems * wallet.getCredits();

		double final_val;
		minVal = 0;

		if (val > minVal) {
			final_val = val;
		}
		else {
			final_val = minVal;
		}

		return final_val;
	}

	private void processCall(CallForBids callForBids) {
		List<Resource> bundle = callForBids.getBundle();
		double val = calculateBundleValue(bundle);

		Bid bid = new Bid(auctionID, bidderId, callForBids.getCallId(), val);
		sendPayload(bid, messageGroupAddress);
	}

	private void processInformBuy(InformBuy informBuy) {
		if (informBuy.getType() == InformBuy.BuyType.WON) {
			numItems -= informBuy.getBundle().size();
		}
	}

	private void processWallet() {
//		totalItemRequired = 0;
//		for (Resource resource: Resource.values()) {
//			totalItemRequired += wallet.get(resource);
//		}
//		double reqOthersPerRes = totalItemRequired * (numBidders - 1) / Resource.values().length;
//
//		estimatedTotalRequired = new HashMap<>();
//		for (Resource resource: Resource.values()) {
//			double estimation = reqOthersPerRes + (-1 * wallet.get(resource));
//			estimatedTotalRequired.put(resource, estimation);
//		}
	}

	private int getTotalItemsRequired() {
		int total = 0;
		for (Resource resource: Resource.values()) {
			int amount = -wallet.get(resource);
			if (amount > 0) {
				total += amount;
			}
		}
		return total;
	}

	private void sendPayload(IFact payload, ICommunicationAddress address) {
		log.info(String.format("Sending %s to %s", payload, address.getName()));
		JiacMessage message = new JiacMessage(payload);
		IActionDescription sendAction = retrieveAction(ICommunicationBean.ACTION_SEND);
		invoke(sendAction, new Serializable[] {message, address});
	}

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

	public StartAuction.Mode getMode() {
		return mode;
	}

	public void setMode(StartAuction.Mode mode) {
		this.mode = mode;
	}

	public int getAuctionID() {
		return auctionID;
	}

	public void setAuctionID(int auctionID) {
		this.auctionID = auctionID;
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

	public double getRiskAversityFactor() {
		return riskAversityFactor;
	}

	public void setRiskAversityFactor(double riskAversityFactor) {
		this.riskAversityFactor = riskAversityFactor;
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

	public int getNumBidders() {
		return numBidders;
	}

	public void setNumBidders(int numBidders) {
		this.numBidders = numBidders;
	}

	public String getMessageGroup() {
		return messageGroup;
	}

	public void setMessageGroup(String messageGroup) {
		this.messageGroup = messageGroup;
	}
}
