/*
 * Copyright (C) 2003-2011 Max Kellermann <max@duempel.org>
 * http://max.kellermann.name/projects/blue-nmea/
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; version 2 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package name.kellermann.max.bluenmea;

import java.util.ArrayList;
import java.io.IOException;

import android.app.Activity;
import android.app.Dialog;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.widget.TextView;
import android.widget.RadioGroup;
import android.widget.ListView;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.view.View;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MenuInflater;
import android.content.Context;
import android.content.DialogInterface;
/*import android.location.GpsStatus;
import android.location.Location;*/
import android.location.LocationManager;
/*import android.location.LocationProvider;*/

public class BlueNMEA extends Activity
    implements RadioGroup.OnCheckedChangeListener,
               Source.StatusListener, Server.Listener {
    private static final String TAG = "BlueNMEA";

    static final int SCANNING_DIALOG = 0;

    Server tcp, bluetoothServer;
    boolean tcp_listen, bt_listen;

    /** the name of the currently selected location provider */
    String locationProvider;

    LocationManager locationManager;

    Source source;

    RadioGroup locationProviderGroup;
    TextView providerStatus, bluetoothServerStatus, tcpStatus;

    ArrayList<Client> clients = new ArrayList<Client>();
    ArrayAdapter<String> clientListAdapter;

    private void ExceptionAlert(Throwable exception, String title) {
        AlertDialog dialog = new AlertDialog.Builder(this).create();
        dialog.setTitle(title);
        dialog.setMessage(exception.getMessage());
        dialog.setButton(DialogInterface.BUTTON_POSITIVE, "OK", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int which) {
                    dialog.cancel();
                }
            });
        dialog.show();
    }

    private void addClient(Client client) {
        clients.add(client);
        clientListAdapter.add(client.toString());
        source.addListener(client);
    }

    private void removeClient(Client client) {
        source.removeListener(client);
        clients.remove(client);
        clientListAdapter.remove(client.toString());
    }

    /** from Activity */
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.main);

        providerStatus = (TextView)findViewById(R.id.providerStatus);
        providerStatus.setText(R.string.status_unknown);
        bluetoothServerStatus = (TextView)findViewById(R.id.bluetoothServerStatus);
        bluetoothServerStatus.setText("not initialized");
        tcpStatus = (TextView)findViewById(R.id.tcpStatus);

        locationProviderGroup = (RadioGroup)findViewById(R.id.provider);
        locationProviderGroup.setOnCheckedChangeListener(this);

        locationProvider = LocationManager.GPS_PROVIDER;
        locationManager = (LocationManager)getSystemService(Context.LOCATION_SERVICE);
        source = new Source(locationManager, this);

        clientListAdapter =
            new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1);
        ListView clientList = (ListView)findViewById(R.id.clients);
        clientList.setAdapter(clientListAdapter);

        clientList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                @Override public void onItemClick(AdapterView<?> parent,
                                                  View view, int position,
                                                  long id) {
                    final Client client = clients.get(position);

                    AlertDialog.Builder builder = new AlertDialog.Builder(BlueNMEA.this);
                    builder.setMessage("Do you want to disconnect the client " + client + "?")
                        .setCancelable(false)
                        .setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog,
                                                    int id) {
                                    if (!clients.contains(client))
                                        return;

                                    removeClient(client);
                                    client.close();
                                }
                            })
                        .setNegativeButton("No", new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog,
                                                    int id) {
                                }
                            });
                    builder.create().show();
                }
            });
        tcp_listen = false;
        try {
            int port = 4352;
            tcp = new TCPServer(this, port);
            tcpStatus.setText("listening on port " + port);
            tcp_listen = true;
        } catch (IOException e) {
            tcpStatus.setText("failed: " + e.getMessage());
        }

        bt_listen = false;
        try {
            bluetoothServer = new ToothServer(this);
            bluetoothServerStatus.setText("listening");
            bt_listen = true;
        } catch (Exception e) {
            bluetoothServerStatus.setText("failed: " + e.getMessage());
        } catch (VerifyError e) {
            bluetoothServerStatus.setText("not available");
        }
    }

    /** from Activity */
    @Override protected void onStop() {
        super.onStop();
        if (tcp_listen) {
            try {
                tcp.close();
            } catch (IOException e) {
            } catch (InterruptedException e) {
            }
        }
        if (bt_listen) {
            try {
                bluetoothServer.close();
            } catch (IOException e) {
            } catch (InterruptedException e) {
            }
        }
    }

    /** from Activity */
    @Override public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.main, menu);
        return true;
    }

    /** from Activity */
    @Override public boolean onPrepareOptionsMenu(Menu menu) {
        menu.findItem(R.id.disconnect_all).setEnabled(!clients.isEmpty());
        return true;
    }

    /** from Activity */
    @Override public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
        case R.id.disconnect_all:
            ArrayList<Client> copy = new ArrayList<Client>(clients);
            for (Client client : copy) {
                removeClient(client);
                client.close();
            }

            return true;

        default:
            return super.onOptionsItemSelected(item);
        }
    }

    class ClientHandler extends Handler {
        public static final int REMOVE = 1;
        public static final int ADD = 2;

        public void handleMessage(Message msg) {
            Client client = (Client)msg.obj;

            switch (msg.what) {
            case ADD:
                addClient(client);
                break;

            case REMOVE:
                if (!clients.contains(client))
                    return;

                removeClient(client);
                break;
            }
        }
    }

    final Handler clientHandler = new ClientHandler();

    /** from RadioGroup.OnCheckedChangeListener */
    @Override public void onCheckedChanged(RadioGroup group, int checkedId) {
        String newLocationProvider;

        if (checkedId == R.id.gpsProvider)
            newLocationProvider = LocationManager.GPS_PROVIDER;
        else if (checkedId == R.id.networkProvider)
            newLocationProvider = LocationManager.NETWORK_PROVIDER;
        else
            return;

        if (newLocationProvider.equals(locationProvider))
            return;

        locationProvider = newLocationProvider;
        source.setLocationProvider(newLocationProvider);
    }

    /** from Source.StatusListener */
    @Override public void onStatusChanged(int status) {
        providerStatus.setText(status);
    }

    /** from Client.Listener */
    @Override public void onClientFailure(Client client, Throwable t) {
        Message msg = clientHandler.obtainMessage(ClientHandler.REMOVE,
                                                  client);
        Bundle b = new Bundle();
        b.putString("error", t.getMessage());
        msg.setData(b);

        clientHandler.sendMessage(msg);

        client.close();
    }

    /** from Server.Listener */
    @Override public void onNewClient(Client client) {
        Message msg = clientHandler.obtainMessage(ClientHandler.ADD,
                                                  client);
        clientHandler.sendMessage(msg);
    }
}
