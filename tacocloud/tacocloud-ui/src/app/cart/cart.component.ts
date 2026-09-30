import { Component, OnInit, Injectable } from '@angular/core';
import { CartService } from './cart-service';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import 'rxjs/add/operator/switchMap';
import { Observable } from 'rxjs/Observable';
import 'rxjs/add/observable/of';

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

  private submitting = false;
  private pendingKey: string;
  private pendingRequest: string;
  private pendingPaymentMethodId: string;

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
    if (this.submitting) {
      return;
    }
    const items = this.cart.getItemsInCart()
        .filter(cartItem => Number(cartItem.quantity) > 0)
        .map(cartItem => ({
          taco: {
            name: cartItem.taco.name,
            ingredientIds: cartItem.taco.ingredients
                .map(ingredient => ingredient.id)
          },
          quantity: Number(cartItem.quantity)
        }));

    const request = Object.assign({}, this.model, {items});
    const requestIdentity = JSON.stringify({request, payment: this.payment});
    if (requestIdentity !== this.pendingRequest) {
      const bytes = new Uint8Array(16);
      window.crypto.getRandomValues(bytes);
      this.pendingKey = Array.from(bytes)
          .map(value => ('0' + value.toString(16)).slice(-2)).join('');
      this.pendingRequest = requestIdentity;
      this.pendingPaymentMethodId = null;
    }
    this.submitting = true;

    const paymentRequest = this.pendingPaymentMethodId
        ? Observable.of({paymentMethodId: this.pendingPaymentMethodId})
        : this.httpClient.post<any>(
        '/api/payment-methods/tokenize',
        this.payment, {
            headers: new HttpHeaders().set('Content-type', 'application/json')
                    .set('Accept', 'application/json'),
        });
    paymentRequest.switchMap(paymentMethod => {
          this.pendingPaymentMethodId = paymentMethod.paymentMethodId;
          request.paymentMethodId = paymentMethod.paymentMethodId;
          return this.httpClient.post(
              '/api/orders', request, {
                headers: new HttpHeaders().set('Content-type', 'application/json')
                        .set('Accept', 'application/json')
                        .set('Idempotency-Key', this.pendingKey)
              });
        }).subscribe(r => {
          this.cart.emptyCart();
          this.pendingKey = null;
          this.pendingRequest = null;
          this.pendingPaymentMethodId = null;
          this.submitting = false;
        }, error => {
          this.submitting = false;
        });

    // TODO: Do something after this...navigate to a thank you page or something
  }

}
