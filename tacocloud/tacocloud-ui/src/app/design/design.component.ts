import { Component, OnInit, Injectable, Input } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router/';
import { CartService } from '../cart/cart-service';

@Component({
  selector: 'taco-design',
  templateUrl: 'design.component.html',
  styleUrls: ['./design.component.css']
})

@Injectable()
export class DesignComponent implements OnInit {

  model = {
    name: '',
    ingredients: []
  };

  allIngredients: any;
  wraps = [];
  proteins = [];
  veggies = [];
  cheeses = [];
  sauces = [];

  constructor(private httpClient: HttpClient, private router: Router, private cart: CartService) {
  }

  // tag::ngOnInit[]
  ngOnInit() {
    this.httpClient.get('http://localhost:8080/api/ingredients')
        .subscribe(data => {
          this.allIngredients = data;
          this.wraps = this.allIngredients.filter(w => w.type === 'WRAP');
          this.proteins = this.allIngredients.filter(p => p.type === 'PROTEIN');
          this.veggies = this.allIngredients.filter(v => v.type === 'VEGGIES');
          this.cheeses = this.allIngredients.filter(c => c.type === 'CHEESE');
          this.sauces = this.allIngredients.filter(s => s.type === 'SAUCE');
        });
  }
  // end::ngOnInit[]

  updateIngredients(ingredient, event) {
    if (event.target.checked) {
      this.model.ingredients.push(ingredient);
    } else {
      this.model.ingredients.splice(this.model.ingredients.findIndex(i => i === ingredient), 1);
    }
  }

  // tag::onSubmit[]
  onSubmit() {
    this.cart.addToCart({
      name: this.model.name,
      ingredients: this.model.ingredients.slice()
    });
    this.router.navigate(['/cart']);
  }
  // end::onSubmit[]

}
