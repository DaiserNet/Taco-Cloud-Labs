import { Observable } from 'rxjs/Observable';
import 'rxjs/add/observable/of';
import { SpecialsComponent } from './specials.component';

describe('SpecialsComponent ratings', () => {
  it('loads the public top and a catalog page', () => {
    const httpClient = jasmine.createSpyObj('HttpClient', ['get', 'put']);
    httpClient.get.and.callFake((path: string) => Observable.of(
      path.indexOf('/top') >= 0
          ? [{tacoId: 'A', tacoName: 'Taco A', average: 4.5, count: 2,
              distribution: [0, 0, 0, 1, 1]}]
          : {content: [{id: 'A', name: 'Taco A'}], page: 0,
              totalPages: 1}));
    const component = new SpecialsComponent(httpClient);

    component.ngOnInit();

    expect(httpClient.get).toHaveBeenCalledWith('/api/tacos/top?limit=10');
    expect(httpClient.get).toHaveBeenCalledWith(
      '/api/tacos?page=0&size=12&sort=name,asc');
    expect(component.topTacos[0].tacoId).toBe('A');
    expect(component.tacos[0].id).toBe('A');
  });

  it('sends only the score and refreshes the top after voting', () => {
    const httpClient = jasmine.createSpyObj('HttpClient', ['get', 'put']);
    httpClient.get.and.returnValue(Observable.of([]));
    httpClient.put.and.returnValue(Observable.of(null));
    const component = new SpecialsComponent(httpClient);
    component.scores['A'] = 5;

    component.rate({id: 'A'});

    expect(httpClient.put).toHaveBeenCalledWith(
      '/api/tacos/A/rating', {score: 5});
    expect(httpClient.get).toHaveBeenCalledWith('/api/tacos/top?limit=10');
    expect(component.ratingMessage).toBe('Your rating was saved.');
  });

  it('does not send a score outside the allowed range', () => {
    const httpClient = jasmine.createSpyObj('HttpClient', ['get', 'put']);
    const component = new SpecialsComponent(httpClient);
    component.scores['A'] = 6;

    component.rate({id: 'A'});

    expect(httpClient.put).not.toHaveBeenCalled();
    expect(component.ratingMessage).toBe('Choose a score from 1 to 5.');
  });
});
