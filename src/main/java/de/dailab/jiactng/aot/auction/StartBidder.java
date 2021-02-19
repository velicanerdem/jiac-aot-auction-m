package de.dailab.jiactng.aot.auction;

import de.dailab.jiactng.agentcore.SimpleAgentNode;

/**
 * Entry point for starting the Bidder Node.
 * Start Bidders first, then Auctioneer.
 */
public class StartBidder {

	public static void main(String[] args) {
		SimpleAgentNode.startAgentNode("bidder.xml", null);
		SimpleAgentNode.startAgentNode("bidder2.xml", null);
		SimpleAgentNode.startAgentNode("bidder3.xml", null);
		SimpleAgentNode.startAgentNode("bidder4.xml", null);
		SimpleAgentNode.startAgentNode("auctioneer.xml", null);
	}
}
