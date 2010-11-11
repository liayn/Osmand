package net.osmand;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import net.osmand.data.Amenity;
import net.osmand.data.AmenityType;
import net.osmand.data.index.IndexConstants;
import net.osmand.osm.Entity;
import net.osmand.osm.LatLon;
import net.osmand.osm.Node;
import net.osmand.osm.io.IOsmStorageFilter;
import net.osmand.osm.io.OsmBaseStorage;
import net.sf.junidecode.Junidecode;

import org.apache.commons.logging.Log;
import org.xml.sax.SAXException;

import android.database.Cursor;
import android.database.sqlite.SQLiteStatement;

public class AmenityIndexRepository extends BaseLocationIndexRepository<Amenity> {
	private static final Log log = LogUtil.getLog(AmenityIndexRepository.class);
	public final static int LIMIT_AMENITIES = 500;

		
	// cache amenities
	private String cFilterId;
	
	
	private final String[] columns = new String[]{"id", "latitude", "longitude", "name", "name_en", "type", "subtype", "opening_hours"};        //$NON-NLS-1$//$NON-NLS-2$//$NON-NLS-3$//$NON-NLS-4$//$NON-NLS-5$//$NON-NLS-6$//$NON-NLS-7$//$NON-NLS-8$
	public List<Amenity> searchAmenities(double topLatitude, double leftLongitude, double bottomLatitude, double rightLongitude, int limit, PoiFilter filter, List<Amenity> amenities){
		long now = System.currentTimeMillis();
		String squery = "? < latitude AND latitude < ? AND ? < longitude AND longitude < ?"; //$NON-NLS-1$
		
		if(filter != null){
			String sql = filter.buildSqlWhereFilter();
			if(sql != null){
				squery += " AND " + sql; //$NON-NLS-1$
			}
		}
		if(limit != -1){
			squery += " ORDER BY RANDOM() LIMIT " +limit; //$NON-NLS-1$
		}
		Cursor query = db.query(IndexConstants.POI_TABLE, columns, squery, 
				new String[]{Double.toString(bottomLatitude), 
				Double.toString(topLatitude), Double.toString(leftLongitude), Double.toString(rightLongitude)}, null, null, null);
		if(query.moveToFirst()){
			do {
				Amenity am = new Amenity();
				am.setId(query.getLong(0));
				am.setLocation(query.getDouble(1), 
							query.getDouble(2));
				am.setName(query.getString(3 ));
				am.setEnName(query.getString(4));
				if(am.getEnName().length() == 0){
					am.setEnName(Junidecode.unidecode(am.getName()));
				}
				am.setType(AmenityType.fromString(query.getString(5)));
				am.setSubType(query.getString(6));
				am.setOpeningHours(query.getString(7));
				amenities.add(am);
				if(limit != -1 && amenities.size() >= limit){
					break;
				}
			} while(query.moveToNext());
		}
		query.close();
		
		if (log.isDebugEnabled()) {
			log.debug(String.format("Search for %s done in %s ms found %s.",  //$NON-NLS-1$
					topLatitude + " " + leftLongitude, System.currentTimeMillis() - now, amenities.size())); //$NON-NLS-1$
		}
		return amenities;
	}
	
	public boolean addAmenity(Amenity a){
		insertAmenities(Collections.singleton(a));
		return true;
	}
	
	public boolean updateAmenity(Amenity a){
		StringBuilder b = new StringBuilder();
		b.append("UPDATE " + IndexConstants.POI_TABLE + " SET "); //$NON-NLS-1$ //$NON-NLS-2$
		b.append(" latitude = ?, "). //$NON-NLS-1$
		  append(" longitude = ?, "). //$NON-NLS-1$
		  append(" opening_hours = ?, "). //$NON-NLS-1$
		  append(" name = ?, "). //$NON-NLS-1$
		  append(" name_en = ?, ").//$NON-NLS-1$
		  append(" type = ?, "). //$NON-NLS-1$
		  append(" subtype = ? "). //$NON-NLS-1$
		  append(" site = ? "). //$NON-NLS-1$
		  append(" phone = ? "). //$NON-NLS-1$
		  append(" WHERE append( id = ?"); //$NON-NLS-1$
		
		db.execSQL(b.toString(),			
				new Object[] { a.getLocation().getLatitude(), a.getLocation().getLongitude(), 
			a.getOpeningHours(), a.getName(), a.getEnName(), AmenityType.valueToString(a.getType()), a.getSubType(),
			a.getSite(), a.getPhone(),  a.getId()});
		return true;
	}
	
	public boolean deleteAmenity(long id){
		db.execSQL("DELETE FROM " + IndexConstants.POI_TABLE+ " WHERE id="+id); //$NON-NLS-1$ //$NON-NLS-2$
		return true;
	}
	
	
	public synchronized void clearCache(){
		super.clearCache();
		cFilterId = null;
	}
	
	public void evaluateCachedAmenities(double topLatitude, double leftLongitude, double bottomLatitude, double rightLongitude, int zoom, int limit,  PoiFilter filter, List<Amenity> toFill){
		cTopLatitude = topLatitude + (topLatitude -bottomLatitude);
		cBottomLatitude = bottomLatitude - (topLatitude -bottomLatitude);
		cLeftLongitude = leftLongitude - (rightLongitude - leftLongitude);
		cRightLongitude = rightLongitude + (rightLongitude - leftLongitude);
		cFilterId = filter == null? null :filter.getFilterId();
		cZoom = zoom;
		// first of all put all entities in temp list in order to not freeze other read threads
		ArrayList<Amenity> tempList = new ArrayList<Amenity>();
		searchAmenities(cTopLatitude, cLeftLongitude, cBottomLatitude, cRightLongitude, limit, filter, tempList);
		synchronized (this) {
			cachedObjects.clear();
			cachedObjects.addAll(tempList);
		}
		
		checkCachedAmenities(topLatitude, leftLongitude, bottomLatitude, rightLongitude, cZoom, filter.getFilterId(), toFill);
	}

	public synchronized boolean checkCachedAmenities(double topLatitude, double leftLongitude, double bottomLatitude, double rightLongitude, int zoom, String filterId, List<Amenity> toFill, boolean fillFound){
		if (db == null) {
			return true;
		}
		boolean inside = cTopLatitude >= topLatitude && cLeftLongitude <= leftLongitude && cRightLongitude >= rightLongitude
				&& cBottomLatitude <= bottomLatitude && zoom == cZoom;
		boolean noNeedToSearch = inside &&  Algoritms.objectEquals(filterId, cFilterId);
		if((inside || fillFound) && toFill != null && Algoritms.objectEquals(filterId, cFilterId)){
			for(Amenity a : cachedObjects){
				LatLon location = a.getLocation();
				if (location.getLatitude() <= topLatitude && location.getLongitude() >= leftLongitude && location.getLongitude() <= rightLongitude
						&& location.getLatitude() >= bottomLatitude) {
					toFill.add(a);
				}
			}
		}
		return noNeedToSearch;
	}
	public boolean checkCachedAmenities(double topLatitude, double leftLongitude, double bottomLatitude, double rightLongitude, int zoom, String filterId, List<Amenity> toFill){
		return checkCachedAmenities(topLatitude, leftLongitude, bottomLatitude, rightLongitude, zoom, filterId, toFill, false);
	}

	public boolean initialize(final IProgress progress, File file) {
		return super.initialize(progress, file, IndexConstants.POI_TABLE_VERSION, IndexConstants.POI_TABLE);
	}
	
	
	
	public boolean updateAmenities(List<Amenity> amenities, double leftLon, double topLat, double rightLon, double bottomLat){
		db.execSQL("DELETE FROM " + IndexConstants.POI_TABLE + " WHERE " + //$NON-NLS-1$ //$NON-NLS-2$
				" longitude >= ? AND ? >= longitude  AND " + //$NON-NLS-1$
				" latitude >= ? AND ? >= latitude ", new Double[] { leftLon, rightLon, bottomLat, topLat }); //$NON-NLS-1$
		
		insertAmenities(amenities);
		return true;
	}

	private void insertAmenities(Collection<Amenity> amenities) {
		SQLiteStatement stat = db.compileStatement("INSERT INTO " + IndexConstants.POI_TABLE +  //$NON-NLS-1$
				"(id, latitude, longitude, name_en, name, type, subtype, opening_hours, site, phone) values(?,?,?,?,?,?,?,?,?)"); //$NON-NLS-1$
		for (Amenity a : amenities) {
			stat.bindLong(1, a.getId());
			stat.bindDouble(2, a.getLocation().getLatitude());
			stat.bindDouble(3, a.getLocation().getLongitude());
			bindString(stat, 4, a.getEnName());
			bindString(stat, 5, a.getName());
			bindString(stat, 6, AmenityType.valueToString(a.getType()));
			bindString(stat, 7, a.getSubType());
			bindString(stat, 8 , a.getOpeningHours());
			bindString(stat, 9, a.getSite());
			bindString(stat, 10, a.getPhone());
			stat.execute();
		}
		stat.close();
	}

	private final static String SITE_API = "http://api.openstreetmap.org/"; //$NON-NLS-1$
	
	public static boolean loadingPOIs(List<Amenity> amenities, double leftLon, double topLat, double righLon, double bottomLat) {
		try {
			// bbox=left,bottom,right,top
			String u = SITE_API+"api/0.6/map?bbox="+leftLon+","+bottomLat+","+righLon+","+topLat;  //$NON-NLS-1$//$NON-NLS-2$//$NON-NLS-3$//$NON-NLS-4$
			URL url = new URL(u);
			log.info("Start loading poi : " + u); //$NON-NLS-1$
			InputStream is = url.openStream();
			OsmBaseStorage st = new OsmBaseStorage();
			final List<Entity> amen = new ArrayList<Entity>();
			st.getFilters().add(new IOsmStorageFilter(){
				@Override
				public boolean acceptEntityToLoad(OsmBaseStorage storage, Entity.EntityId id, Entity entity) {
					if(Amenity.isAmenity(entity)){
						amen.add(entity);
						return true;
					}
					// to 
					return entity instanceof Node;
				}
			});
			st.parseOSM(is, null, null, false);
			for (Entity e : amen) {
				Amenity am = new Amenity(e);
				if(am.getEnName().length() == 0){
					am.setEnName(Junidecode.unidecode(am.getName()));
				}
				amenities.add(am);
			}
			log.info("Loaded " +amenities.size() + " amenities");  //$NON-NLS-1$//$NON-NLS-2$
		} catch (IOException e) {
			log.error("Loading nodes failed", e); //$NON-NLS-1$
			return false;
		} catch (SAXException e) {
			log.error("Loading nodes failed", e); //$NON-NLS-1$
			return false;
		}
		return true;
	}
}
