import { Component, OnInit } from '@angular/core';
import { HttpClient } from '@angular/common/http';

@Component({
  selector: 'taco-specials',
  templateUrl: 'specials.component.html'
})

export class SpecialsComponent implements OnInit {
  topTacos: any[] = [];
  tacos: any[] = [];
  scores: {[tacoId: string]: number} = {};
  page = 0;
  totalPages = 0;
  ratingMessage: string;

  constructor(private httpClient: HttpClient) { }

  ngOnInit() {
    this.loadTop();
    this.loadCatalog(0);
  }

  loadTop() {
    this.httpClient.get('/api/tacos/top?limit=10')
        .subscribe((top: any[]) => this.topTacos = top);
  }

  loadCatalog(page: number) {
    if (page < 0 || page >= this.totalPages && this.totalPages > 0) {
      return;
    }
    this.httpClient.get('/api/tacos?page=' + page
        + '&size=12&sort=name,asc').subscribe((result: any) => {
          this.tacos = result.content;
          this.page = result.page;
          this.totalPages = result.totalPages;
        });
  }

  rate(taco: any) {
    const score = Number(this.scores[taco.id]);
    if (score < 1 || score > 5 || isNaN(score)) {
      this.ratingMessage = 'Choose a score from 1 to 5.';
      return;
    }
    this.httpClient.put('/api/tacos/' + encodeURIComponent(taco.id)
        + '/rating', {score: score}).subscribe(() => {
          this.ratingMessage = 'Your rating was saved.';
          this.loadTop();
        }, (error: any) => {
          this.ratingMessage = error.status === 401 || error.status === 403
              ? 'Sign in to rate tacos.' : 'The rating could not be saved.';
        });
  }
}
