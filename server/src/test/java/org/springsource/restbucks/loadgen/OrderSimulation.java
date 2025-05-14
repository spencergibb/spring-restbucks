package org.springsource.restbucks.loadgen;

import static io.gatling.http.HeaderValues.*;
import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

import io.gatling.app.Gatling;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Session;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.hateoas.IanaLinkRelations;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.mediatype.hal.HalLinkDiscoverer;
import org.springsource.restbucks.payment.CreditCardNumber;
import org.springsource.restbucks.payment.web.PaymentLinks;

import com.fasterxml.jackson.databind.ObjectMapper;

public class OrderSimulation extends Simulation {

	private static final Duration DURATION = Duration.ofMinutes(30);
	private static final HalLinkDiscoverer LINKS = new HalLinkDiscoverer();
	private static final CreditCardNumber CCN = CreditCardNumber.of("1234123412341234");

	private static final int USERS_AT_ONCE = 15;
	private static final ObjectMapper objectMapper = new ObjectMapper();

	// HTTP configuration
	HttpProtocolBuilder httpProtocol = http
			.baseUrl("http://localhost:8080")
			.acceptHeader(ApplicationJson())
			.contentTypeHeader(ApplicationJson());

	ScenarioBuilder scn = scenario("Place Orders Scenario")

			.exec(
					http("Get Drinks List")
							.get("/drinks")
							.check(bodyString().saveAs("drinksResponse")) //
			)

			.exec(session -> {

				var response = session.getString("drinksResponse");
				var urls = LINKS.recursive()
						.findLinksWithRel("restbucks:drink", response)
						.stream().map(Link::getHref).toList();

				// Save the list of drink links to session
				return session.set("drinkLinks", urls);
			})

			.exec(session -> {

				var drinkLinks = session.getList("drinkLinks");

				var random = ThreadLocalRandom.current();
				var drinks = drinkLinks.get(random.nextInt(drinkLinks.size()));
				var location = random.nextBoolean() ? "To go" : "In store";

				return session
						.set("orderRequestBody", "{ \"drinks\" : [\"%s\"], \"location\" : \"%s\" }".formatted(drinks, location));

			})
			.exec(
					http("Create Order")
							.post("/orders")
							.body(StringBody(it -> it.getString("orderRequestBody"))).asJson()
							.check(status().in(201))
							.check(bodyString().saveAs("orderResponse")) //
			)
			.exec(session -> {

				// Extract the order ID from the order response
				var response = session.getString("orderResponse");
				var payment = LINKS.findLinkWithRel(PaymentLinks.PAYMENT_REL, response);
				var self = LINKS.findRequiredLinkWithRel(IanaLinkRelations.SELF, response);

				return session
						.set("payment", payment.map(Link::getHref).orElse(null))
						.set("order", self.getHref())
						.set("paymentRequestBody", "{\"number\":\"%s\"}".formatted(CCN));

			}).exec(

					http("Submit Payment")
							.put(session -> session.get("payment"))
							.body(StringBody(session -> session.getString("paymentRequestBody"))).asJson()
							.check(bodyString().saveAs("paymentResponse"))
							.check(status().in(201)) //
			)
			.asLongAs(this::orderIsNotReady)
			.on(
					exec(
							http("Get Order")
									.get(it -> it.get("order"))
									.check(bodyString().saveAs("orderResponse"))
									.check(status().in(200, 304))

					).exec(session -> {

						var orderResponse = session.getString("orderResponse");
						var receiptLink = LINKS.findLinkWithRel(PaymentLinks.RECEIPT_REL, orderResponse);

						return session
								.set("receipt", receiptLink.map(Link::getHref).orElse(null))
								.set("orderStatus", readOrder(orderResponse));

					}).pause(Duration.ofSeconds(1)))
			.exec(

					http("Take Order") //
							.delete(it -> it.get("receipt")) //
							.check(status().in(200)) //
			);

	private boolean orderIsNotReady(Session session) {

		return session.getString("receipt") == null
				&& !"Ready".equals(session.getString("orderStatus"));
	}

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
		// setUp(scn.injectOpen(atOnceUsers(USERS_AT_ONCE))).protocols(httpProtocol);
	}

	public static void main(String[] args) {

		Gatling.main(new String[] {
				"-s", OrderSimulation.class.getName(),
				"-rf", "target/gatling-results"
		});
	}
}
