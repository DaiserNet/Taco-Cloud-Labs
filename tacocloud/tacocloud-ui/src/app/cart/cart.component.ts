import { Component, OnInit, Injectable } from '@angular/core';
import { CartService } from './cart-service';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import 'rxjs/add/operator/switchMap';

@Component({
  selector: 'taco-cart',
  templateUrl: 'cart.component.html',
  styleUrls: ['./cart.component.css']
})

@Injectable()
export class CartComponent implements OnInit {

  model = {
    deliveryName: '',
    deliveryStreet: '',
    deliveryCity: '',
    deliveryState: '',
    deliveryZip: '',
    paymentMethodId: '',
    items: []
  };

  payment = {
    pan: '',
    expiration: '',
    cvv: ''
  };

  constructor(private cart: CartService, private httpClient: HttpClient) {
    this.cart = cart;
  }

  ngOnInit() {}

  get cartItems() {
    return this.cart.getItemsInCart();
  }

  get cartTotal() {
    return this.cart.getCartTotal();
  }

  onSubmit() {
    this.model.items = this.cart.getItemsInCart()
        .filter(cartItem => Number(cartItem.quantity) > 0)
        .map(cartItem => ({
          taco: {
            name: cartItem.taco.name,
            ingredientIds: cartItem.taco.ingredients
                .map(ingredient => ingredient.id)
          },
          quantity: Number(cartItem.quantity)
        }));

    this.httpClient.post<any>(
        'http://localhost:8080/api/payment-methods/tokenize',
        this.payment, {
            headers: new HttpHeaders().set('Content-type', 'application/json')
                    .set('Accept', 'application/json'),
        }).switchMap(paymentMethod => {
          this.model.paymentMethodId = paymentMethod.paymentMethodId;
          return this.httpClient.post(
              'http://localhost:8080/api/orders', this.model, {
                headers: new HttpHeaders().set('Content-type', 'application/json')
                        .set('Accept', 'application/json')
              });
        }).subscribe(r => this.cart.emptyCart());

    // TODO: Do something after this...navigate to a thank you page or something
  }

}
