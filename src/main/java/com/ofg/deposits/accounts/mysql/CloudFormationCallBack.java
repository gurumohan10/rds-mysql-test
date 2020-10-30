package com.ofg.deposits.accounts.mysql;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import org.json.JSONObject;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DBInstance;
import software.amazon.awssdk.services.rds.model.DescribeDbInstancesRequest;
import software.amazon.awssdk.services.rds.model.DescribeDbInstancesResponse;
import software.amazon.awssdk.services.rds.model.Endpoint;

/**
 * Handler for requests to Lambda function.
 */
public class CloudFormationCallBack implements RequestHandler<Map<String, Object>, Object> {

    public Object handleRequest(Map<String, Object> input, Context context) {

        Map<String, String> resourceProperties = (Map<String, String>) input.get("ResourceProperties");

        //write your own logic here. The properties mentioned in the cloudformation template under custom resource will
        //be present in "ResourceProperties" of the input. For this example, I am creating a user for the database created
        //in the previous step by cloudformation
        createUserInDatabase(resourceProperties, context);


        //below method will send a call back to cloudformation. The URL to which callback should be sent will be given
        //in the input data by cloudformation. If no callback is received within an hour, cloudformation will rollback
        //all the resources that are created by it.
        sendCallBackToCloudFormation(input, context);
        return null;
    }

    private void createUserInDatabase(Map<String, String> resourceProperties, Context context) {
    	context.getLogger().log("createUserInDatabase method started");
        RdsClient client = RdsClient.builder().region(Region.US_EAST_1).build();
        DescribeDbInstancesResponse dbInstancesResponse = client.describeDBInstances(
                DescribeDbInstancesRequest.builder()
                        .dbInstanceIdentifier(resourceProperties.get("DBInstanceIdentifier"))
                        .build());
        DBInstance dbInstance = dbInstancesResponse.dbInstances().get(0);   //write your logic to identify your dbInstance
        Endpoint endpoint = dbInstance.endpoint();
        context.getLogger().log("DB Endpoint : "+ endpoint);
        String url = "jdbc:mysql://" + endpoint.address() + ":" + endpoint.port();
        String masterUsername = resourceProperties.get("MasterUsername");
        String masterPassword = resourceProperties.get("MasterUserPassword");
        
        context.getLogger().log("URL : "+ url);
        context.getLogger().log("Master Uname : "+ masterUsername);
        context.getLogger().log("Master Password: "+ masterPassword);

        Connection conn = null;
        try {
            conn = DriverManager.getConnection(url, masterUsername, masterPassword);
            context.getLogger().log("Connection Established  ");
            Statement stmt = conn.createStatement();
            context.getLogger().log("getting stmt ");
            String userName = "guru";
            String userPwd = "guru123";

            stmt.execute("CREATE USER '" + userName + "'@'%' IDENTIFIED BY '" + userPwd + "' ;");
            stmt.execute("GRANT CREATE,DELETE,SELECT, SHOW DATABASES ON *.* TO '" + userName + "'@'%' WITH GRANT OPTION;");
            stmt.execute("CREATE DATABASE testing;");
            context.getLogger().log("User Created");
        } catch (SQLException e) {
            e.printStackTrace();
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        }

        client.close();
    }

    private void sendCallBackToCloudFormation(Map<String, Object> input, Context context) {
    	context.getLogger().log("sendCallBackToCloudFormation method started");
        String responseUrl = (String) input.get("ResponseURL");
        context.getLogger().log("ResponseURL: " + responseUrl);
        try {
            URL s3URL = new URL(responseUrl);
            HttpURLConnection connection = (HttpURLConnection) s3URL.openConnection();
            connection.setDoOutput(true);
            connection.setRequestMethod("PUT");

            JSONObject responseBody = new JSONObject();
            responseBody.put("Status", "SUCCESS");//or send FAILED incase of failed task
            responseBody.put("PhysicalResourceId", context.getLogStreamName());
            responseBody.put("StackId", input.get("StackId"));
            responseBody.put("RequestId", input.get("RequestId"));
            responseBody.put("LogicalResourceId", input.get("LogicalResourceId"));
//            responseBody.put("Data", "responseData");

            OutputStreamWriter response = new OutputStreamWriter(connection.getOutputStream());
            response.write(responseBody.toString());
            response.close();
            context.getLogger().log("Response Code: " + connection.getResponseCode());
            context.getLogger().log("sendCallBackToCloudFormation method ended");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
