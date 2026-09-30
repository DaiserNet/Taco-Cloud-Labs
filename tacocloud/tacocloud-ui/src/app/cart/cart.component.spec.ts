import { Observable } from 'rxjs/Observable';
import 'rxjs/add/observable/of';
import 'rxjs/add/observable/throw';
import { Subject } from 'rxjs/Subject';

import { CartComponent } from './cart.component';
import { CartService } from './cart-service';

describe('CartComponent order payload', () => {
  it('should send quantity and ingredient IDs without client totals', () => {
    const cart = new CartService();
    cart.addToCart({
      name: 'Quantity taco',
      ingredients: [
        {id: 'WRAP', unitPrice: 1.10},
        {id: 'SLSA', unitPrice: 0.35}
      ]
    });
    cart.getItemsInCart()[0].quantity = 3;
    const httpClient = jasmine.createSpyObj('HttpClient', ['post']);
    httpClient.post.and.callFake((url: string) => url.indexOf('payment-methods') >= 0
        ? Observable.of({paymentMethodId: 'PAYMENT-ID'})
        : Observable.of({total: 4.35}));
    const component = new CartComponent(cart, httpClient);

    component.onSubmit();

    const request = httpClient.post.calls.argsFor(1)[1];
    expect(httpClient.post.calls.argsFor(0)[0]).toBe('/api/payment-methods/tokenize');
    expect(httpClient.post.calls.argsFor(1)[0]).toBe('/api/orders');
    const headers = httpClient.post.calls.argsFor(1)[2].headers;
    expect(headers.get('Idempotency-Key')).toMatch(/^[a-f0-9]{32}$/);
    expect(request.items[0].quantity).toBe(3);
    expect(request.items[0].taco).toEqual({
      name: 'Quantity taco',
      ingredientIds: ['WRAP', 'SLSA']
    });
    expect(request.total).toBeUndefined();
    expect(request.subtotal).toBeUndefined();
    expect(request.tacos).toBeUndefined();
    expect(cart.getItemsInCart().length).toBe(0);
  });

  it('should ignore a double click while an order is in flight', () => {
    const cart = new CartService();
    cart.addToCart({name: 'Taco', ingredients: [{id: 'WRAP'}]});
    const pending = new Subject<any>();
    const httpClient = jasmine.createSpyObj('HttpClient', ['post']);
    httpClient.post.and.callFake((url: string) => url.indexOf('payment-methods') >= 0
        ? Observable.of({paymentMethodId: 'PAYMENT-ID'}) : pending);
    const component = new CartComponent(cart, httpClient);

    component.onSubmit();
    component.onSubmit();

    expect(httpClient.post.calls.count()).toBe(2);
    pending.next({id: 'ORDER-ID'});
    pending.complete();
    expect(cart.getItemsInCart().length).toBe(0);
  });

  it('should reuse the same key and payment token after a failed order request', () => {
    const cart = new CartService();
    cart.addToCart({name: 'Taco', ingredients: [{id: 'WRAP'}]});
    const httpClient = jasmine.createSpyObj('HttpClient', ['post']);
    let orderCalls = 0;
    httpClient.post.and.callFake((url: string) => {
      if (url.indexOf('payment-methods') >= 0) {
        return Observable.of({paymentMethodId: 'PAYMENT-ID'});
      }
      orderCalls++;
      return orderCalls === 1 ? Observable.throw('network')
          : Observable.of({id: 'ORDER-ID'});
    });
    const component = new CartComponent(cart, httpClient);

    component.onSubmit();
    const firstKey = httpClient.post.calls.argsFor(1)[2].headers.get('Idempotency-Key');
    component.onSubmit();

    expect(httpClient.post.calls.count()).toBe(3);
    expect(httpClient.post.calls.argsFor(2)[2].headers.get('Idempotency-Key'))
        .toBe(firstKey);
    expect(httpClient.post.calls.argsFor(2)[1].paymentMethodId).toBe('PAYMENT-ID');
    expect(cart.getItemsInCart().length).toBe(0);
  });
});
