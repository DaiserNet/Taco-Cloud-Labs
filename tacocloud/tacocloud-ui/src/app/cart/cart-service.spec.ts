import { CartService } from './cart-service';

describe('CartService pricing', () => {
  it('should keep a quantity greater than one and calculate its preview total', () => {
    const service = new CartService();
    service.addToCart({
      name: 'Decimal taco',
      ingredients: [
        {id: 'WRAP', unitPrice: 1.10},
        {id: 'SLSA', unitPrice: 0.35}
      ]
    });

    service.getItemsInCart()[0].quantity = 3;

    expect(service.getItemsInCart()[0].unitPrice).toBe(1.45);
    expect(service.getItemsInCart()[0].lineTotal).toBe(4.35);
    expect(service.getCartTotal()).toBe(4.35);
  });
});
