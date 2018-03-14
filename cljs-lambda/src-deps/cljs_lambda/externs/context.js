var context = {};

context.awsRequestId;
context.clientContext;
context.logGroupName;
context.logStreamName;
context.functionName;
context.callbackWaitsForEmptyEventLoop;

context.getMemoryLimitInMB = function() {};
context.getFunctionName = function() {};
context.getAwsRequestId = function() {};
context.getLogStreamName = function() {};
context.getClientContext = function() {};
context.getIdentity = function() {};
context.getRemainingTimeInMillis = function() {};
context.getLogger = function() {};

context.succeed = function(result) {};
context.fail = function(error) {};
context.done = function(error, result) {};
