/*
 * Copyright 2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springsource.restbucks.drinks;

import lombok.Getter;
import org.jmolecules.ddd.types.AggregateRoot;
import org.jmolecules.ddd.types.Identifier;
import org.springsource.restbucks.drinks.Drink.DrinkIdentifier;

import javax.money.MonetaryAmount;
import java.util.UUID;

/**
 * @author Oliver Drotbohm
 */
@Getter
public class Drink implements AggregateRoot<Drink, DrinkIdentifier> {

	private DrinkIdentifier id;
	private String name;
	private Milk milk;
	private Size size;
	private MonetaryAmount price;

	public Drink(String name, Milk milk, Size size, MonetaryAmount price) {

		this.id = new DrinkIdentifier(UUID.randomUUID());
		this.name = name;
		this.milk = milk;
		this.size = size;
		this.price = price;
	}

	public record DrinkIdentifier(UUID id) implements Identifier {}
}
