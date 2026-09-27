import { Observable } from 'rxjs/Observable';
import 'rxjs/add/observable/of';

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
});
