export class CartItem {

  quantity = 1;

  taco: any;

  constructor(taco: any) {
    this.taco = taco;
  }

  get unitPrice() {
    const ingredients = this.taco && this.taco.ingredients
        ? this.taco.ingredients : [];
    const total = ingredients.reduce(
        (sum, ingredient) => sum + Number(ingredient.unitPrice || 0), 0);
    return Math.round(total * 100) / 100;
  }

  get lineTotal() {
    return Math.round(this.quantity * this.unitPrice * 100) / 100;
  }

}
