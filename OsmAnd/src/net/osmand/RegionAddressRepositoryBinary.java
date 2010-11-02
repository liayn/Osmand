package net.osmand;

import java.io.IOException;
import java.text.Collator;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import net.osmand.binary.BinaryMapIndexReader;
import net.osmand.data.Building;
import net.osmand.data.City;
import net.osmand.data.MapObject;
import net.osmand.data.MapObjectComparator;
import net.osmand.data.PostCode;
import net.osmand.data.Street;
import net.osmand.osm.LatLon;

import org.apache.commons.logging.Log;


public class RegionAddressRepositoryBinary implements RegionAddressRepository {
	private static final Log log = LogUtil.getLog(RegionAddressRepositoryBinary.class);
	private BinaryMapIndexReader file;
	private String region;
	
	
	private LinkedHashMap<Long, City> cities = new LinkedHashMap<Long, City>();
	private Map<String, PostCode> postCodes = new TreeMap<String, PostCode>(Collator.getInstance());
	private boolean useEnglishNames = false;
	
	private Comparator<MapObject> comparator = new MapObjectComparator(useEnglishNames); 
	
	
	public RegionAddressRepositoryBinary(BinaryMapIndexReader file, String name) {
		this.file = file;
		this.region = name;
	}
	
	public void close(){
		this.file = null;
	}

	@Override
	public boolean isMapRepository() {
		return true;
	}

	@Override
	public void fillWithSuggestedBuildings(PostCode postcode, Street street, String name, List<Building> buildingsToFill) {
		preloadBuildings(street);
		if(name.length() == 0){
			buildingsToFill.addAll(street.getBuildings());
			return;
		}
		name = name.toLowerCase();
		int ind = 0;
		for (Building building : street.getBuildings()) {
			String bName = useEnglishNames ? building.getEnName() : building.getName();
			String lowerCase = bName.toLowerCase();
			if (lowerCase.startsWith(name)) {
				buildingsToFill.add(ind, building);
				ind++;
			} else if (lowerCase.contains(name)) {
				buildingsToFill.add(building);
			}
		}
		Collections.sort(buildingsToFill, comparator);
	}
		
	private void preloadBuildings(Street street) {
		if(street.getBuildings().isEmpty()){
			try {
				file.preloadBuildings(street);
			} catch (IOException e) {
				log.error("Disk operation failed" , e); //$NON-NLS-1$
			}
		}		
	}


	@Override
	public void fillWithSuggestedStreets(MapObject o, String name, List<Street> streetsToFill) {
		assert o instanceof PostCode || o instanceof City;
		City city = (City) (o instanceof City ? o : null); 
		PostCode post = (PostCode) (o instanceof PostCode ? o : null);
		preloadStreets(o);
		name = name.toLowerCase();
		
		Collection<Street> streets = post == null ? city.getStreets() : post.getStreets() ; 
		
		if(name.length() == 0){
			streetsToFill.addAll(streets);
		} else {
			int ind = 0;
			for (Street s : streets) {
				String sName = useEnglishNames ? s.getEnName() : s.getName();
				String lowerCase = sName.toLowerCase();
				if (lowerCase.startsWith(name)) {
					streetsToFill.add(ind, s);
					ind++;
				} else if (lowerCase.contains(name)) {
					streetsToFill.add(s);
				}
			}
		}
		Collections.sort(streetsToFill, comparator);
	
		
	}

	private void preloadStreets(MapObject o) {
		assert o instanceof PostCode || o instanceof City;
		try {
			if(o instanceof PostCode){
				file.preloadStreets((PostCode) o);
			} else {
				file.preloadStreets((City) o);
			}
		} catch (IOException e) {
			log.error("Disk operation failed" , e); //$NON-NLS-1$
		}
		
	}
	

	@Override
	public void fillWithSuggestedCities(String name, List<MapObject> citiesToFill, LatLon currentLocation) {
		preloadCities();
		try {
			// essentially index is created that cities towns are first in cities map
			int ind = 0;
			if (name.length() >= 2 &&
					   Character.isDigit(name.charAt(0)) &&
					   Character.isDigit(name.charAt(1))) {
				// also try to identify postcodes
				String uName = name.toUpperCase();
				for (PostCode code : file.getPostcodes(region)) {
					if (code.getName().startsWith(uName)) {
						citiesToFill.add(ind++, code);
					} else if(code.getName().contains(uName)){
						citiesToFill.add(code);
					}
				}
				
			}
			if (name.length() < 3) {
				if (name.length() == 0) {
					citiesToFill.addAll(cities.values());
				} else {
					name = name.toLowerCase();
					for (City c : cities.values()) {
						String cName = useEnglishNames ? c.getEnName() : c.getName();
						String lowerCase = cName.toLowerCase();
						if (lowerCase.startsWith(name)) {
							citiesToFill.add(c);
						}
					}
				}
			} else {
				name = name.toLowerCase();
				Collection<City> src = cities.values();
				for (City c : src) {
					String cName = useEnglishNames ? c.getEnName() : c.getName();
					String lowerCase = cName.toLowerCase();
					if (lowerCase.startsWith(name)) {
						citiesToFill.add(ind, c);
						ind++;
					} else if (lowerCase.contains(name)) {
						citiesToFill.add(c);
					}
				}
				int initialsize = citiesToFill.size();
				
				for(City c : file.getVillages(name)){
					String cName = useEnglishNames ? c.getEnName() : c.getName();
					String lowerCase = cName.toLowerCase();
					if (lowerCase.startsWith(name)) {
						citiesToFill.add(ind, c);
						ind++;
					} else if (lowerCase.contains(name)) {
						citiesToFill.add(c);
					}
				}
				log.debug("Loaded citites " + (citiesToFill.size() - initialsize)); //$NON-NLS-1$
			}
		} catch (IOException e) {
			log.error("Disk operation failed" , e); //$NON-NLS-1$
		}
		
	}

	@Override
	public void fillWithSuggestedStreetsIntersectStreets(City city, Street st, List<Street> streetsToFill) {
		// TODO Auto-generated method stub
	}
	
	@Override
	public LatLon findStreetIntersection(Street street, Street street2) {
		// TODO Auto-generated method stub
		return null;
	}
	

	@Override
	public Building getBuildingByName(Street street, String name) {
		preloadBuildings(street);
		for (Building b : street.getBuildings()) {
			String bName = useEnglishNames ? b.getEnName() : b.getName();
			if (bName.equals(name)) {
				return b;
			}
		}
		return null;
	}

	@Override
	public String getName() {
		return region;
	}

	@Override
	public boolean useEnglishNames() {
		return useEnglishNames;
	}
	


	@Override
	public City getCityById(Long id) {
		if(id == -1){
			// do not preload cities for that case
			return null;
		}
		preloadCities();
		return cities.get(id);
	}


	private void preloadCities() {
		if (cities.isEmpty()) {
			try {
				List<City> cs = file.getCities(region);
				for (City c : cs) {
					cities.put(c.getId(), c);
				}
			} catch (IOException e) {
				log.error("Disk operation failed", e); //$NON-NLS-1$
			}
		}
	}

	@Override
	public PostCode getPostcode(String name) {
		if(name == null){
			return null;
		}
		String uc = name.toUpperCase();
		if(!postCodes.containsKey(uc)){
			try {
				postCodes.put(uc, file.getPostcodeByName(this.region, name));
			} catch (IOException e) {
				log.error("Disk operation failed", e); //$NON-NLS-1$
			}
		}
		return postCodes.get(uc);
	}


	@Override
	public Street getStreetByName(MapObject o, String name) {
		assert o instanceof PostCode || o instanceof City;
		City city = (City) (o instanceof City ? o : null);
		PostCode post = (PostCode) (o instanceof PostCode ? o : null);
		preloadStreets(o);
		name = name.toLowerCase();
		Collection<Street> streets = post == null ? city.getStreets() : post.getStreets();
		for (Street s : streets) {
			String sName = useEnglishNames ? s.getEnName() : s.getName();
			String lowerCase = sName.toLowerCase();
			if (lowerCase.equals(name)) {
				return s;
			}
		}
		return null;
	}



	@Override
	public void setUseEnglishNames(boolean useEnglishNames) {
		this.useEnglishNames = useEnglishNames;
		this.comparator = new MapObjectComparator(useEnglishNames);
	}

	@Override
	public void addCityToPreloadedList(City city) {
		cities.put(city.getId(), city);
	}

	@Override
	public boolean areCitiesPreloaded() {
		return !cities.isEmpty();
	}

	@Override
	public boolean arePostcodesPreloaded() {
		// postcodes are always preloaded 
		// do not load them into memory (just cache last used)
		return true;
	}

	@Override
	public void clearCache() {
		cities.clear();
		postCodes.clear();
		
	}



}
