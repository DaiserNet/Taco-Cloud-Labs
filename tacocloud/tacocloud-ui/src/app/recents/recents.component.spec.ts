import { Observable } from 'rxjs/Observable';
import 'rxjs/add/observable/of';
import { RecentTacosComponent } from './recents.component';

describe('RecentTacosComponent', () => {
  it('loads the first page from the catalog API', () => {
    const httpClient = jasmine.createSpyObj('HttpClient', ['get']);
    httpClient.get.and.returnValue(Observable.of({
      content: [{id: 'A', name: 'Alpha taco'}],
      page: 0,
      size: 12,
      totalElements: 1,
      totalPages: 1
    }));
    const component = new RecentTacosComponent(httpClient);

    component.ngOnInit();

    expect(httpClient.get).toHaveBeenCalledWith(
      '/api/tacos?page=0&size=12&sort=createdAt,desc');
    expect(component.recentTacos).toEqual([{id: 'A', name: 'Alpha taco'}]);
  });
});
