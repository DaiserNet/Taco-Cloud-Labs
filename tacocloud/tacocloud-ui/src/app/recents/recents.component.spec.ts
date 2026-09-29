import { Observable } from 'rxjs/Observable';
import 'rxjs/add/observable/of';
import { RecentTacosComponent } from './recents.component';

describe('RecentTacosComponent', () => {
  it('loads the first page from the catalog API', () => {
    const httpClient = jasmine.createSpyObj('HttpClient', ['get']);
    httpClient.get.and.callFake((path: string) => Observable.of(
      path.indexOf('/api/tacos') === 0
          ? {content: [{id: 'A', name: 'Alpha taco'}], totalPages: 1}
          : {content: [], totalPages: 0}));
    const component = new RecentTacosComponent(httpClient);

    component.ngOnInit();

    expect(httpClient.get).toHaveBeenCalledWith(
      '/api/tacos?page=0&size=12&sort=createdAt,desc');
    expect(component.recentTacos).toEqual([{id: 'A', name: 'Alpha taco'}]);
  });

  it('restores favorite state from the server after a reload', () => {
    const httpClient = jasmine.createSpyObj('HttpClient', ['get', 'put', 'delete']);
    httpClient.get.and.callFake((path: string) => Observable.of(
      path.indexOf('/api/tacos') === 0
          ? {content: [{id: 'A', name: 'Alpha taco'}], totalPages: 1}
          : {content: [{tacoId: 'A', tacoName: 'Alpha taco', orphaned: false}],
              totalPages: 1}));

    const component = new RecentTacosComponent(httpClient);
    component.ngOnInit();
    expect(component.isFavorite('A')).toBe(true);

    const reloaded = new RecentTacosComponent(httpClient);
    reloaded.ngOnInit();
    expect(reloaded.isFavorite('A')).toBe(true);
    expect(httpClient.get).toHaveBeenCalledWith(
      '/api/users/me/favorites?page=0&size=50');
  });

  it('uses PUT and DELETE without sending a user ID', () => {
    const httpClient = jasmine.createSpyObj('HttpClient', ['get', 'put', 'delete']);
    httpClient.put.and.returnValue(Observable.of(null));
    httpClient.delete.and.returnValue(Observable.of(null));
    const component = new RecentTacosComponent(httpClient);

    component.toggleFavorite({id: 'A'});
    expect(httpClient.put).toHaveBeenCalledWith(
      '/api/users/me/favorites/A', {});
    expect(component.isFavorite('A')).toBe(true);

    component.toggleFavorite({id: 'A'});
    expect(httpClient.delete).toHaveBeenCalledWith(
      '/api/users/me/favorites/A');
    expect(component.isFavorite('A')).toBe(false);
  });
});
