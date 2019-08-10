/**
 * Copyright Google Inc. All Rights Reserved.
 *
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.firebase.udacity.friendlychat

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.util.Log
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.Toast
import androidx.annotation.NonNull

import com.example.firebasechatapp.R
import com.firebase.ui.auth.AuthUI
import com.google.android.gms.auth.api.Auth
import com.google.android.gms.common.Scopes
import com.google.android.gms.common.api.TransformedResult
import com.google.android.gms.tasks.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.StorageReference
import com.google.firebase.storage.UploadTask
import java.lang.Exception
import java.util.*
import kotlin.collections.HashMap

class MainActivity : AppCompatActivity() {

    private var mMessageListView: ListView? = null
    private var FriendlyMessageLengthKey = "MSG_LIMIT_LENGTH"
    private var mMessageAdapter: MessageAdapter? = null
    private var mProgressBar: ProgressBar? = null
    private var mPhotoPickerButton: ImageButton? = null
    private var mMessageEditText: EditText? = null
    private var mSendButton: Button? = null
    private var mFirebaseDatabase: FirebaseDatabase? = null
    private var mDbReference: DatabaseReference? = null

    private var mFirebaseStorage: FirebaseStorage? = null
    private var mStorageReference: StorageReference? = null

    private var mChildEventListener: ChildEventListener? = null
    private var mFirebaseAuth: FirebaseAuth? = null
    private var authStateListener: FirebaseAuth.AuthStateListener? = null

    private var mUsername: String? = null
    private lateinit var remoteConfig: FirebaseRemoteConfig
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mUsername = ANONYMOUS
        //remote config
        remoteConfig = FirebaseRemoteConfig.getInstance()

        val configSetting = FirebaseRemoteConfigSettings.Builder().setMinimumFetchIntervalInSeconds(4200).build()
        remoteConfig.setConfigSettings(configSetting)
        var defaultConfigMap = HashMap<String, Any>()
        defaultConfigMap[FriendlyMessageLengthKey] = DEFAULT_MSG_LENGTH_LIMIT
        remoteConfig.setDefaults(defaultConfigMap)

        fetchConfig()


        mFirebaseDatabase = FirebaseDatabase.getInstance()
        mFirebaseAuth = FirebaseAuth.getInstance()
        mFirebaseStorage = FirebaseStorage.getInstance()

        mDbReference = mFirebaseDatabase!!.getReference("data")
        mStorageReference = mFirebaseStorage!!.getReference("chat_photos")

        // Initialize references to views
        mProgressBar = findViewById(R.id.progressBar)
        mMessageListView = findViewById(R.id.messageListView)
        mPhotoPickerButton = findViewById(R.id.photoPickerButton)
        mMessageEditText = findViewById(R.id.messageEditText)
        mSendButton = findViewById(R.id.sendButton)

        // Initialize message ListView and its adapter
        val friendlyMessages = ArrayList<FriendlyMessage>()
        mMessageAdapter = MessageAdapter(this, R.layout.item_message, friendlyMessages)
        mMessageListView!!.adapter = mMessageAdapter

        // Initialize progress bar
        mProgressBar!!.visibility = ProgressBar.INVISIBLE

        // ImagePickerButton shows an image picker to upload a image for a message
        mPhotoPickerButton!!.setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT)
            intent.type = "image/jpeg"
            intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true)
            startActivityForResult(Intent.createChooser(intent, "Complete action using"), RC_PHOTO_PICKER)
        }

        // Enable Send button when there's text to send
        mMessageEditText!!.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(charSequence: CharSequence, i: Int, i1: Int, i2: Int) {}

            override fun onTextChanged(charSequence: CharSequence, i: Int, i1: Int, i2: Int) {
                if (charSequence.toString().trim { it <= ' ' }.length > 0) {
                    mSendButton!!.isEnabled = true
                } else {
                    mSendButton!!.isEnabled = false
                }
            }

            override fun afterTextChanged(editable: Editable) {}
        })
        mMessageEditText!!.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(DEFAULT_MSG_LENGTH_LIMIT))

        // Send button sends a message and clears the EditText
        mSendButton!!.setOnClickListener {
            val friendlyMessage = FriendlyMessage(mMessageEditText!!.text.toString(), mUsername, null)

            val key = mDbReference!!.push().key
            Log.e(TAG, "key : " + key!!)
            mDbReference!!.child(key).setValue(friendlyMessage)
            mDbReference!!.child(key).addValueEventListener(object : ValueEventListener {
                override fun onDataChange(dataSnapshot: DataSnapshot) {
                    //FriendlyMessage u = (FriendlyMessage) dataSnapshot.getValue();
                    Log.e(TAG, "user : $dataSnapshot")
                }

                override fun onCancelled(databaseError: DatabaseError) {
                    Log.e(TAG, "Failed to read value." + databaseError.toException())
                }
            })
            // Clear input box
            mMessageEditText!!.text.clear()
        }

        val googleIdp = AuthUI.IdpConfig.GoogleBuilder()
                .setScopes(Arrays.asList(Scopes.GAMES))
                .build()
        /*final AuthUI.IdpConfig facebookIdp = new AuthUI.IdpConfig.FacebookBuilder()
                .setPermissions(Arrays.asList("user_friends"))
                .build();*/
        authStateListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            val user = firebaseAuth.currentUser
            if (user == null) {

                onSignOutCleanup()
                //not logged in
                startActivityForResult(
                        AuthUI.getInstance()
                                .createSignInIntentBuilder()
                                .setIsSmartLockEnabled(false)
                                .setAvailableProviders(Arrays.asList(AuthUI.IdpConfig.EmailBuilder().build()))
                                .build(),
                        RC_SIGN_IN)
            } else {
                //login
                Toast.makeText(this@MainActivity, "welcome to friendly chat", Toast.LENGTH_LONG).show()
                onSignInInitialized(user.displayName)
            }
        }


    }

    private fun fetchConfig() {

        remoteConfig.fetch(5).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                remoteConfig.activateFetched()
                val updated = task.result
                Log.e(TAG, "Config params updated: $updated")
                applyFetchedLength()
            } else {
                Toast.makeText(this, "Fetch failed",
                        Toast.LENGTH_SHORT).show()
            }


        }.addOnFailureListener {

        }
    }
    private fun applyFetchedLength() {

        var length = remoteConfig.getLong(FriendlyMessageLengthKey)
        Log.e(TAG, "length : $length")
        mMessageEditText?.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(length.toInt()))
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RC_SIGN_IN) {
            if (resultCode == Activity.RESULT_OK) {
                Toast.makeText(this@MainActivity, "ok", Toast.LENGTH_LONG).show()
            } else if (resultCode == Activity.RESULT_CANCELED) {
                Toast.makeText(this@MainActivity, "cancelled", Toast.LENGTH_LONG).show()
                finish()
            }
        } else if (requestCode == RC_PHOTO_PICKER && resultCode == Activity.RESULT_OK) {
            val selectedUri = data!!.data

            //get a ref of file to upload
            val photoRef: StorageReference = mStorageReference!!.child(selectedUri!!.lastPathSegment!!)

            try {
                var uploadTask: UploadTask = photoRef.putFile(selectedUri);

                val urlTask = uploadTask.continueWithTask(Continuation<UploadTask.TaskSnapshot, Task<Uri>> { task ->
                    if (!task.isSuccessful) {
                        task.exception?.let {
                            throw it
                        }
                    }
                    return@Continuation photoRef.downloadUrl
                }).addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        val uri = task.result
                        Log.e(TAG, "url : ${uri}")
                        val friendlyMessage = FriendlyMessage(null, mUsername, uri.toString());
                        mDbReference?.push()?.setValue(friendlyMessage);


                    } else {
                        // Handle failures
                        // ...
                    }
                }


            } catch (e: Exception) {
                e.printStackTrace()
            }

        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val inflater = menuInflater
        inflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return if (item.itemId == R.id.sign_out_menu) {
            mFirebaseAuth!!.signOut()
            true
        } else
            super.onOptionsItemSelected(item)
    }

    override fun onResume() {
        super.onResume()
        fetchConfig()
        mFirebaseAuth!!.addAuthStateListener(authStateListener!!)
    }

    override fun onPause() {
        super.onPause()
        if (mFirebaseAuth != null)
            mFirebaseAuth!!.removeAuthStateListener(authStateListener!!)

        detacheEventListener()
        mMessageAdapter!!.clear()
    }

    private fun onSignInInitialized(name: String?) {
        mUsername = name
        attachEventListener()

    }

    private fun onSignOutCleanup() {
        mUsername = ANONYMOUS
        mMessageAdapter!!.clear()
        detacheEventListener()
    }

    private fun detacheEventListener() {
        if (mChildEventListener != null)
            mDbReference!!.removeEventListener(mChildEventListener!!)
    }

    private fun attachEventListener() {
        if (mChildEventListener == null) {
            mChildEventListener = object : ChildEventListener {
                override fun onChildAdded(dataSnapshot: DataSnapshot, s: String?) {

                    Log.e(TAG, "test ")
                    val message = dataSnapshot.getValue(FriendlyMessage::class.java!!)
                    mMessageAdapter!!.add(message)
                }

                override fun onChildChanged(dataSnapshot: DataSnapshot, s: String?) {

                }

                override fun onChildRemoved(dataSnapshot: DataSnapshot) {

                }

                override fun onChildMoved(dataSnapshot: DataSnapshot, s: String?) {

                }

                override fun onCancelled(databaseError: DatabaseError) {

                }
            }
        }
        mDbReference!!.addChildEventListener(mChildEventListener!!)
    }

    companion object {

        private val TAG = "MainActivity"

        val ANONYMOUS = "anonymous"
        val DEFAULT_MSG_LENGTH_LIMIT = 1000
        val RC_PHOTO_PICKER = 10001
        val RC_SIGN_IN = 10002
    }

}
