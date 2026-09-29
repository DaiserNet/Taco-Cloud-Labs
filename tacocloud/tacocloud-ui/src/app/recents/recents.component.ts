import { Component, OnInit, Injectable } from '@angular/core';
import { Http } from '@angular/http';
import { HttpClient } from '@angular/common/http';

@Component({
  selector: 'recent-tacos',
  templateUrl: 'recents.component.html',
  styleUrls: ['./recents.component.css']
})

@Injectable()
export class RecentTacosComponent implements OnInit {
  recentTacos: any;
  favoriteIds: {[id: string]: boolean} = {};
  favoriteMessage: string;

  constructor(private httpClient: HttpClient) { }

  ngOnInit() {
    this.httpClient.get('/api/tacos?page=0&size=12&sort=createdAt,desc')
        .subscribe((data: any) => this.recentTacos = data.content);
    this.loadFavorites(0);
  }

  isFavorite(tacoId: string): boolean {
    return !!this.favoriteIds[tacoId];
  }

  toggleFavorite(taco: any) {
    const path = '/api/users/me/favorites/' + encodeURIComponent(taco.id);
    const wasFavorite = this.isFavorite(taco.id);
    const request = wasFavorite
        ? this.httpClient.delete(path) : this.httpClient.put(path, {});
    request.subscribe(() => {
      this.favoriteIds[taco.id] = !wasFavorite;
      this.favoriteMessage = null;
    }, (error: any) => {
      this.favoriteMessage = error.status === 401 || error.status === 403
          ? 'Sign in to save favorite tacos.'
          : 'The favorite could not be updated.';
    });
  }

  private loadFavorites(page: number) {
    this.httpClient.get('/api/users/me/favorites?page=' + page + '&size=50')
        .subscribe((data: any) => {
          data.content.forEach((favorite: any) => {
            if (!favorite.orphaned) {
              this.favoriteIds[favorite.tacoId] = true;
            }
          });
          if (page + 1 < data.totalPages) {
            this.loadFavorites(page + 1);
          }
        }, (error: any) => {
          if (error.status !== 401 && error.status !== 403) {
            this.favoriteMessage = 'Favorites could not be loaded.';
          }
        });
  }
}
