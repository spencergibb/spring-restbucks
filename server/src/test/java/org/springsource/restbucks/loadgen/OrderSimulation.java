package org.springsource.restbucks.loadgen;

import static io.gatling.http.HeaderValues.*;
import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.hateoas.IanaLinkRelations;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.mediatype.hal.HalLinkDiscoverer;
import org.springsource.restbucks.drinks.DrinksOptions;
import org.springsource.restbucks.payment.CreditCardNumber;
import org.springsource.restbucks.payment.web.PaymentLinks;

import com.fasterxml.jackson.databind.ObjectMapper;

public class OrderSimulation extends Simulation {

	private static final Duration DURATION = Duration.ofMinutes(120);
	private static final HalLinkDiscoverer LINKS = new HalLinkDiscoverer();
	private static final CreditCardNumber CCN = CreditCardNumber.of("1234123412341234");

	private static final int USERS_AT_ONCE = 2;
	private static final ObjectMapper objectMapper = new ObjectMapper();

	// HTTP configuration
	HttpProtocolBuilder httpProtocol = http
			.baseUrl("http://localhost:8080")
			.acceptHeader(ApplicationJson())
			.contentTypeHeader(ApplicationJson());

	// Scenario to make requests to /drinks and /orders
	ScenarioBuilder scn = scenario("Drinks Order Scenario").exec(

			http("Get Drinks List")
					.get("/drinks")
					.check(bodyString().saveAs("drinksResponse")))

			.exec(session -> {

				var response = session.getString("drinksResponse");
				var urls = LINKS.recursive()
						.findLinksWithRel(DrinksOptions.DRINKS_REL, response)
						.stream().map(Link::getHref).toList();

				// Save the list of drink links to session
				return session.set("drinkLinks", urls);

			})
			.asLongAs(session -> !session.getList("drinkLinks").isEmpty())
			.on(exec(session -> {

				var drinkLinks = session.getList("drinkLinks");

				// Randomly select a drink
				var drink = drinkLinks.get(ThreadLocalRandom.current().nextInt(drinkLinks.size()));

				// Randomize location
				var location = ThreadLocalRandom.current().nextBoolean() ? "To go" : "In store";

				// Create order request body
				var orderRequestBody = String.format("{ \"drinks\" : [\"%s\"], \"location\" : \"%s\" }", drink, location);

				// Save the request body to session
				return session.set("orderRequestBody", orderRequestBody);

			}).exec(

					http("Create Order")
							.post("/orders") //
							.body(StringBody(session -> session.getString("orderRequestBody"))).asJson() //
							.check(status().in(200, 201)) // Expecting valid status or errors
							.check(bodyString().saveAs("orderResponse")) // Save order response to session

			).exec(session -> {

				// Extract the order ID from the order response
				var response = session.getString("orderResponse");
				var payment = LINKS.findLinkWithRel(PaymentLinks.PAYMENT_REL, response);
				var self = LINKS.findRequiredLinkWithRel(IanaLinkRelations.SELF, response);

				return session
						.set("payment", payment.map(Link::getHref).orElse(null)) //
						.set("order", self.getHref()); //

			}).exec(session -> {

				// Save the payment request body to session
				return session.set("paymentRequestBody", String.format("{\"number\":\"%s\"}", CCN));

			}).exec(

					http("Submit Payment") //
							.put(session -> session.get("payment")) //
							.body(StringBody(session -> session.getString("paymentRequestBody"))).asJson() //
							.check(bodyString().saveAs("paymentResponse")) //
							.check(status().in(200, 201)) // Expecting valid status

			).asLongAs(session -> session.getString("receipt") == null && !"Taken".equals(session.getString("orderStatus")))
					.on(pause(1) //
							.exec(

									http("Get Order") //
											.get(session -> session.get("order")) //
											.check(bodyString().saveAs("orderResponse")) // Save order response to session
											.check(status().in(200, 201, 404))// Expecting valid status

							).exec(session -> {

								var orderResponse = session.getString("orderResponse"); //
								var receiptLink = LINKS.findLinkWithRel(PaymentLinks.RECEIPT_REL, orderResponse);

								return session
										.set("receipt", receiptLink.map(Link::getHref).orElse(null))
										.set("orderStatus", readOrder(orderResponse));
							}))
					.exec(

							http("Take Order") //
									.delete(session -> session.get("receipt")) //
									.check(status().in(200, 201, 404, 500)) //
					));

	private String readOrder(String orderResponse) {

		var orderStatus = "";

		try {

			var rootNode = objectMapper.readTree(orderResponse);
			var orderStatusLinkNode = rootNode.at("/status");

			if (orderStatusLinkNode != null) {
				// Extract the order ID from the URL (assuming last segment is the ID)
				orderStatus = orderStatusLinkNode.asText();
			}

		} catch (Exception e) {
			throw new RuntimeException(e);
		}

		return orderStatus;
	}

	{
		setUp(scn.injectOpen(constantUsersPerSec(USERS_AT_ONCE).during(DURATION))).protocols(httpProtocol);
	}
}
