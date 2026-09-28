import { DesignComponent } from './design.component';
import { CartService } from '../cart/cart-service';

describe('DesignComponent order design', () => {
  it('keeps the design in the cart without writing to the admin taco catalog', () => {
    const httpClient = jasmine.createSpyObj('HttpClient', ['get', 'post']);
    const router = jasmine.createSpyObj('Router', ['navigate']);
    const cart = new CartService();
    const component = new DesignComponent(httpClient, router, cart);
    component.model.name = 'My taco';
    component.model.ingredients.push({id: 'FLTO', name: 'Flour Tortilla'});

    component.onSubmit();

    expect(httpClient.post).not.toHaveBeenCalled();
    expect(cart.getItemsInCart()[0].taco).toEqual({
      name: 'My taco',
      ingredients: [{id: 'FLTO', name: 'Flour Tortilla'}]
    });
    expect(router.navigate).toHaveBeenCalledWith(['/cart']);
  });
});
