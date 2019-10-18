package com.unimelbs.parkingassistant.model;

import android.content.Context;
import android.os.AsyncTask;
import android.util.Log;

import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;

import com.google.android.gms.maps.model.LatLng;
import com.uber.autodispose.android.lifecycle.AndroidLifecycleScopeProvider;
import com.unimelbs.parkingassistant.R;
import com.unimelbs.parkingassistant.parkingapi.ParkingApi;
import com.unimelbs.parkingassistant.parkingapi.SiteState;
import com.unimelbs.parkingassistant.parkingapi.SitesStateGetQuery;
import com.unimelbs.parkingassistant.util.Timer;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;

import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

import static com.uber.autodispose.AutoDispose.autoDisposable;

public class DataFeed extends AsyncTask<Void,Void,Void> {
    private static final String TAG = "DataFeed";
    private static final String BAYS_FILE = "bays";
    private static final long DAY_TO_MILLIS = 1000*60*60*24;
    private static final long MINUTE_TO_MILLIS = 1000*60;
    private static final int FRESHNESS_INTERVAL_DAYS = 1;

    private Context context;
    private LifecycleOwner lifecycleOwner;
    private List<Bay> bays;
    private Hashtable<Integer,Bay> baysHashtable;
    private ParkingApi api;
    //private BayStateApi bayStateApi;


    public DataFeed (LifecycleOwner mainActivity,
                     Context context) {
        this.lifecycleOwner = mainActivity;
        this.context = context;
        this.bays = new ArrayList<>();
        this.api = ParkingApi.getInstance();
        //this.bayStateApi = new BayStateApi(this);
    }

/*
    class BayStateApi extends AsyncTask<Void,Void,List<SiteState>>
    {
        private static final String TAG = "BayStateApi";
        private DataFeed dataFeed;
        private List<SiteState> baysStates;
        public BayStateApi(DataFeed dataFeed, LatLng centrePoint)
        {
            this.dataFeed = dataFeed;
        }
        private void fetchApiData()
        {
            SitesStateGetQuery query = new SitesStateGetQuery(-37.796201, 144.958266, null);
            dataFeed.api.sitesStateGet(query)
                .subscribeOn(Schedulers.io())
                //.observeOn(AndroidSchedulers.mainThread()) // to return to the main thread
                //.as(autoDisposable(AndroidLifecycleScopeProvider.from(this, Lifecycle.Event.ON_STOP))) //to dispose when the activity finishes
                .subscribe(value ->
                        {
                            baysStates = value;
                            System.out.println("Value:" + value.get(0).getStatus()); // sample, other values are id, status, location, zone, recordState
                        },
                    throwable -> Log.d("debug", throwable.getMessage()) // do this on error
                );
        }


        @Override
        protected Void doInBackground(Void...params) {
            fetchApiData();
            return null; //new LatLng(50,60);//null;
        }
    }
 */

    class BayDataApi extends AsyncTask<Void,Void,Void>
    {
        private static final String TAG = "BayDataApi";
        private BayAdapter bayAdapter;
        private DataFeed dataFeed;


        public BayDataApi(DataFeed dataFeed)
        {
            Log.d(TAG, "BayDataApi: passed data feed is:"+dataFeed);
            this.dataFeed = dataFeed;
        }
        private void fetchApiData()
        {
            Log.d(TAG, "fetchApiData: started on thread:"+Thread.currentThread().getName());
            this.bayAdapter = new BayAdapter(this.dataFeed);
            ParkingApi api = ParkingApi.getInstance();
            Timer timer = new Timer();
            timer.start();
            api.sitesGet()
                    .subscribeOn(Schedulers.io())
                    //.observeOn(AndroidSchedulers.mainThread())
                    //.as(autoDisposable(AndroidLifecycleScopeProvider.from(getLifecycle(), Lifecycle.Event.ON_STOP)))
                    .subscribe(value ->
                            {
                                timer.stop();
                                Log.d(TAG, "fetchApiData: completed in "+
                                        timer.getDuration()+" seconds. # of Fetched sites:"+
                                        value.size());
                                bayAdapter.convertSites(value);
                            },
                            throwable -> Log.d(TAG+"-throwable", throwable.getMessage()));

        }
        @Override
        protected Void doInBackground(Void... voids) {
            this.fetchApiData();
            return null;
        }
    }


    public void loadData()
    {
        Log.d(TAG, "loadData: loading data started on thread: "+Thread.currentThread().getName());
        if (dataFilesExist())
        {
            if(isDataFresh())
            {
                Log.d(TAG, "loadData: data is fresh - loading from local file.");
                loadBaysFromFile();
            }
            else
            {
                Log.d(TAG, "loadData: data is stale, showing current data " +
                        "and fetching fresh data from API.");
                loadBaysFromFile();
                new BayDataApi(this).execute();
                saveBaysToFile();
            }
        }
        else
        {
            Log.d(TAG, "loadData: data files don't exist. loading from raw/bays."+
                    " Calling the API async to download data");
            loadBaysFromRaw();
            new BayDataApi(this).execute();
            saveBaysToFile();
        }
    }

    private boolean dataFilesExist()
    {
        boolean result=true;
        File baysFile = context.getFileStreamPath(BAYS_FILE);
        if(baysFile==null||!baysFile.exists())result=false;
        return result;
    }

    private boolean isDataFresh()
    {
        boolean result=true;
        long currentTime = System.currentTimeMillis();
        File baysFile = context.getFileStreamPath(BAYS_FILE);
        if((currentTime-baysFile.lastModified())/DAY_TO_MILLIS>FRESHNESS_INTERVAL_DAYS)result=false;
        return result;
    }


    public List<Bay> getItems() {
        Log.d(TAG, "getItems: bays has "+bays.size()+" entries.");
        return this.bays;
    }


    private void loadBaysFromFile()
    {
        Timer timer = new Timer();
        timer.start();
        try
        {
            FileInputStream fileInputStream = context.openFileInput(BAYS_FILE);
            BufferedInputStream bufferedInputStream = new BufferedInputStream(fileInputStream);
            ObjectInputStream objectInputStream = new ObjectInputStream(bufferedInputStream);

            this.bays = (List<Bay>) objectInputStream.readObject();
            timer.stop();
            Log.d(TAG, "loadBaysFromFile: ended in "+timer.getDuration()+" seconds. Number of bays loaded:"+this.bays.size());
            fileInputStream.close();
            objectInputStream.close();
        }  catch (FileNotFoundException e) {
            Log.d(TAG, "loadBaysFromFile: FileNotFoundException: "+e.getMessage());
        } catch (Exception e) {
            Log.d(TAG, "loadBaysFromFile: general exception: "+e.getMessage());
        }
    }
    private void loadBaysFromRaw()
    {
        Timer timer = new Timer();
        timer.start();
        try
        {
            BufferedInputStream bufferedInputStream =
                    new BufferedInputStream (context.getResources().openRawResource(R.raw.bays));

            ObjectInputStream objectInputStream = new ObjectInputStream(bufferedInputStream);
            bays = (List<Bay>) objectInputStream.readObject();

            timer.stop();
            Log.d(TAG, "loadBaysFromRaw: completed in "+timer.getDuration()+" seconds. Bays loaded: "+bays.size());

            bufferedInputStream.close();
            objectInputStream.close();
        }  catch (FileNotFoundException e) {
            Log.d(TAG, "loadBaysFromFile: FileNotFoundException: "+e.getMessage());
        } catch (Exception e) {
            Log.d(TAG, "loadBaysFromFile: general exception: "+e.getMessage());
        }
    }


    private void saveBaysToFile()
    {
        Log.d(TAG, "saveBaysToFile: ");
        File file = new File(context.getFilesDir()+"/"+BAYS_FILE);
        if (file.exists())
        {
            Log.d(TAG, "saveBaysToFile: a file exists, deleting it.");
            file.delete();
        }


        try {
            FileOutputStream fileOutputStream =  context.openFileOutput(BAYS_FILE, Context.MODE_PRIVATE);
            ObjectOutputStream objectOutputStream = new ObjectOutputStream(fileOutputStream);
            Log.d(TAG, "saveBaysToFile: num of bays: "+this.bays.size());
            objectOutputStream.writeObject(this.bays);
            objectOutputStream.close();
            fileOutputStream.close();
        } catch (Exception e) {
            Log.d(TAG, "saveBaysToFile: "+e.getMessage());
        }
    }


    public void addBay(Bay bay)
    {
        this.bays.add(bay);
    }




    @Override
    protected Void doInBackground(Void... voids) {
        loadData();
        return null;
    }


}
