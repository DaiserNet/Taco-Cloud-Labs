import { Injectable } from '@angular/core';
import { ApiService } from '../api/ApiService';

@Injectable()
export class RecentTacosService {

  constructor(private apiService: ApiService) {
  }

  getRecentTacos() {
    return this.apiService.get('/api/tacos?page=0&size=12&sort=createdAt,desc');
  }

}
